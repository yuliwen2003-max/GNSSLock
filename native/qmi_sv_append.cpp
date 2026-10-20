// Fixed, one-shot experiment using the HAL's existing B6 request. The original
// probe stays read-only; reuse its identity, SHA/ELF and fixed-site validation.
// The renamed original entry point is never called by this executable.
#include <cinttypes>
#define main gpslock_readonly_probe_entry_not_called
#include "qmi_probe.cpp"
#undef main

namespace {
constexpr uint64_t kPreVa = 0x269d0, kPostVa = 0x269d4;
constexpr uint32_t kAfterPost = 0x2a080008;
constexpr uint64_t kHigh31Add = 0x7fffffff00000000ULL;
constexpr uint64_t kHigh31Clear = 0x80000000ffffffffULL;
constexpr uint32_t kExecuteEl0FourBytes = (0xfu << 5) | (2u << 1) | 1u;

// Used for rollback after SIGINT/SIGTERM; deliberately does not check cancelled.
void rollbackRead(int fd, uint64_t address, void* output, size_t size) {
  if (address > INT64_MAX || size > INT64_MAX - address) throw Error("rollback read overflow");
  size_t done = 0;
  while (done < size) {
    ssize_t got = pread(fd, static_cast<char*>(output) + done, size - done,
                        static_cast<off_t>(address + done));
    if (got < 0 && errno == EINTR) continue;
    if (got <= 0) throw Error(got < 0 ? sysError("read fixed rollback field") : "short fixed rollback read");
    done += static_cast<size_t>(got);
  }
}
void writeClearField(int fd, uint64_t frame, uint64_t value) {
  if (frame % 16 || frame > static_cast<uint64_t>(INT64_MAX) - 0xb0)
    throw Error("invalid fixed request frame");
  // This is the only process-memory write in the entire experiment.
  ssize_t got;
  do { got = pwrite(fd, &value, sizeof(value), static_cast<off_t>(frame + 0xa8)); }
  while (got < 0 && errno == EINTR);
  if (got != sizeof(value)) throw Error(got < 0 ? sysError("write fixed BDS clear mask") : "short fixed BDS clear-mask write");
}
void checkInstructions(int fd, uint64_t pre, bool rollback = false) {
  uint32_t code[3]{};
  if (rollback) rollbackRead(fd, pre, code, sizeof(code));
  else readAt(fd, pre, code, sizeof(code));
  if (code[0] != kSatellite.callInstruction || code[1] != kSatellite.watchInstruction ||
      code[2] != kAfterPost) throw Error("B6 call/return/adjacent instruction guard failed");
}

class AppendTrace {
 public:
  explicit AppendTrace(pid_t tid) : tid_(tid) {
    if (ptrace(PTRACE_SEIZE, tid_, nullptr, nullptr)) throw Error(sysError("PTRACE_SEIZE"));
    attached_ = true;
  }
  ~AppendTrace() { close(); }
  void stop() {
    if (!prepareCleanup()) throw Error("cannot stop target thread");
    checkCancel();
    if (signal_) throw Error("real signal arrived while attaching");
  }
  unsigned inspect() {
    iovec io{&original_, sizeof(original_)};
    if (ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io))
      throw Error(sysError("hardware execute breakpoints unavailable"));
    const unsigned count = original_.dbg_info & 0xff;
    if (!count || count > 16 || io.iov_len < 8 + count * 16 || io.iov_len > sizeof(original_))
      throw Error("unsupported hardware breakpoint regset");
    for (unsigned i = 0; i < count; ++i)
      if (original_.dbg_regs[i].ctrl & 1u) throw Error("target already has an enabled hardware breakpoint");
    haveOriginal_ = true;
    slots_ = count;
    return count;
  }
  void arm(uint64_t pre, uint64_t post) {
    if (!stopped_ || !haveOriginal_ || slots_ < 2) throw Error("two disabled hardware execute slots are required");
    pre_ = pre;
    post_ = post;
    auto state = original_;
    state.dbg_regs[0].addr = pre;
    state.dbg_regs[1].addr = post;
    for (unsigned i = 0; i < 2; ++i) {
      state.dbg_regs[i].ctrl = kExecuteEl0FourBytes;
      state.dbg_regs[i].pad = 0;
    }
    dirty_ = true;
    iovec io{&state, 40};
    if (ptrace(PTRACE_SETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io))
      throw Error(sysError("install two hardware breakpoints"));
    auto check = debugState();
    for (unsigned i = 0; i < 2; ++i)
      if (check.dbg_regs[i].addr != state.dbg_regs[i].addr ||
          check.dbg_regs[i].ctrl != state.dbg_regs[i].ctrl)
        throw Error("two-breakpoint readback failed");
    resume();
  }
  // 0=running; 1=our pre-call; 2=our post-call. Other stops refuse the test.
  unsigned event(user_pt_regs& regs) {
    collect();
    if (!attached_) throw Error("target thread exited during experiment");
    if (!stopped_) return 0;
    if (signal_ != SIGTRAP || !ownTrap(regs))
      throw Error("another signal interrupted the experiment");
    signal_ = 0;
    return regs.pc == pre_ ? 1 : 2;
  }
  void disablePre() {
    if (!stopped_ || !dirty_) throw Error("pre-call breakpoint is not stopped");
    auto state = original_;
    iovec io{&state, 24};
    if (ptrace(PTRACE_SETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io))
      throw Error(sysError("disable pre-call breakpoint"));
    const auto check = debugState();
    if ((check.dbg_regs[0].ctrl & 1u) || check.dbg_regs[1].addr != post_ ||
        check.dbg_regs[1].ctrl != kExecuteEl0FourBytes)
      throw Error("pre-call disable/post-call enable verification failed");
  }
  void resume() {
    if (!stopped_ || signal_) throw Error("cannot resume unexpected signal stop");
    if (ptrace(PTRACE_CONT, tid_, nullptr, nullptr)) throw Error(sysError("PTRACE_CONT"));
    stopped_ = false;
  }
  bool prepareCleanup() noexcept {
    if (!attached_) return false;
    if (!stopped_ && ptrace(PTRACE_INTERRUPT, tid_, nullptr, nullptr) != 0 && errno != ESRCH) return false;
    const auto deadline = Clock::now() + std::chrono::seconds(2);
    do {
      try { collect(); } catch (...) {}
      if (!attached_) return false;
      if (stopped_) {
        user_pt_regs regs{};
        if (signal_ == SIGTRAP && ownTrap(regs)) signal_ = 0;
        return true;
      }
      poll(nullptr, 0, 1);
    } while (Clock::now() < deadline);
    return false;
  }
  bool stoppedRegisters(user_pt_regs& regs) noexcept {
    if (!attached_ || !stopped_) return false;
    iovec io{&regs, sizeof(regs)};
    return ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0 &&
        io.iov_len == sizeof(regs);
  }
  bool close() noexcept {
    if (!attached_) return true;
    if (!prepareCleanup()) return !attached_;
    bool restored = !dirty_;
    if (dirty_) {
      iovec io{&original_, 40};
      const int setRc = ptrace(PTRACE_SETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io);
      const int setError = setRc == 0 ? 0 : errno;
      user_hwdebug_state check{};
      io = {&check, sizeof(check)};
      const int getRc = ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io);
      const int getError = getRc == 0 ? 0 : errno;
      // Both original slots were disabled. Linux can normalize their address
      // and control bits, so require only the two enable bits to be cleared.
      restored = getRc == 0 && io.iov_len >= 40 &&
          !(check.dbg_regs[0].ctrl & 1u) && !(check.dbg_regs[1].ctrl & 1u);
      std::printf("{\"event\":\"restore_hardware\",\"set_errno\":%d,\"get_errno\":%d,\"slot0_disabled\":%s,\"slot1_disabled\":%s,\"verified\":%s}\n",
          setError, getError, getRc == 0 && !(check.dbg_regs[0].ctrl & 1u) ? "true" : "false",
          getRc == 0 && !(check.dbg_regs[1].ctrl & 1u) ? "true" : "false", restored ? "true" : "false");
      if (restored) dirty_ = false;
    }
    const int detachRc = ptrace(PTRACE_DETACH, tid_, nullptr,
        reinterpret_cast<void*>(static_cast<intptr_t>(signal_)));
    const int detachError = detachRc == 0 ? 0 : errno;
    if (detachRc == 0) attached_ = false;
    std::printf("{\"event\":\"detach\",\"success\":%s,\"errno\":%d}\n",
        detachRc == 0 ? "true" : "false", detachError);
    return restored && detachRc == 0;
  }
 private:
  user_hwdebug_state debugState() {
    user_hwdebug_state state{};
    iovec io{&state, sizeof(state)};
    if (ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io) || io.iov_len < 40)
      throw Error("read two hardware slots failed");
    return state;
  }
  bool ownTrap(user_pt_regs& regs) noexcept {
    if (!dirty_ || !pre_ || !post_) return false;
    siginfo_t info{};
    return ptrace(PTRACE_GETSIGINFO, tid_, nullptr, &info) == 0 && info.si_code == TRAP_HWBKPT &&
        stoppedRegisters(regs) && (regs.pc == pre_ || regs.pc == post_);
  }
  void collect() {
    if (!attached_ || stopped_) return;
    int status = 0;
    const auto got = waitpid(tid_, &status, __WALL | WNOHANG);
    if (!got || (got < 0 && errno == EINTR)) return;
    if (got < 0) {
      if ((errno == ECHILD || errno == ESRCH) && access(proc(tid_, "stat").c_str(), F_OK) != 0 && errno == ENOENT) {
        attached_ = false;
        dirty_ = false;
        return;
      }
      throw Error(sysError("wait traced thread"));
    }
    if (WIFEXITED(status) || WIFSIGNALED(status)) { attached_ = false; dirty_ = false; return; }
    if (!WIFSTOPPED(status)) throw Error("unexpected ptrace wait status");
    stopped_ = true;
    signal_ = WSTOPSIG(status);
    if ((static_cast<unsigned>(status) >> 16) == PTRACE_EVENT_STOP && signal_ == SIGTRAP) signal_ = 0;
  }
  pid_t tid_;
  bool attached_ = false, stopped_ = false, dirty_ = false, haveOriginal_ = false;
  int signal_ = 0;
  unsigned slots_ = 0;
  uint64_t pre_ = 0, post_ = 0;
  user_hwdebug_state original_{};
};
} // namespace

int main(int argc, char** argv) {
  setvbuf(stdout, nullptr, _IONBF, 0);
  std::unique_ptr<AppendTrace> trace;
  std::unique_ptr<Fd> memory;
  uint64_t frame = 0, pre = 0, post = 0;
  bool writeAttempted = false, observedPost = false;
  const char* stackRestore = "not_needed";
  int rc = 1;
  try {
    struct sigaction action{};
    action.sa_handler = onSignal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, nullptr) || sigaction(SIGTERM, &action, nullptr))
      throw Error(sysError("signal handlers"));
    if (getuid() || geteuid()) throw Error("root is required");
    pid_t pid = 0, tid = 0;
    unsigned seconds = 10;
    bool append = false, modeSeen = false, secondsSeen = false;
    for (int i = 1; i < argc; ++i) {
      const std::string option = argv[i];
      if ((option == "--inspect" || option == "--append-test") && !modeSeen) {
        append = option == "--append-test";
        modeSeen = true;
        continue;
      }
      if (++i >= argc) throw Error("usage: gpslock-qmi-sv-append --pid PID --tid TID [--seconds 1..20] [--inspect|--append-test]");
      if (option == "--pid" && !pid) pid = static_cast<pid_t>(number(argv[i], 10, INT_MAX));
      else if (option == "--tid" && !tid) tid = static_cast<pid_t>(number(argv[i], 10, INT_MAX));
      else if (option == "--seconds" && !secondsSeen) {
        seconds = static_cast<unsigned>(number(argv[i], 10, 20));
        secondsSeen = true;
      } else throw Error("unknown or duplicate argument");
    }
    if (pid <= 1 || tid <= 1 || pid == getpid() || !seconds || seconds > 20)
      throw Error("explicit valid PID/TID and seconds 1..20 are required");
    const Identity identity(pid, tid);
    Library library(pid, kSatellite);
    post = library.address(pid);
    pre = post - (kPostVa - kPreVa);
    memory = std::make_unique<Fd>(open(proc(pid, "mem").c_str(),
        (append ? O_RDWR : O_RDONLY) | O_CLOEXEC));
    if (memory->value < 0) throw Error(sysError("open GNSS process memory"));
    trace = std::make_unique<AppendTrace>(tid);
    trace->stop();
    identity.verify();
    if (library.address(pid) != post) throw Error("library mapping changed");
    checkInstructions(memory->value, pre);
    const auto slots = trace->inspect();
    std::printf("{\"event\":\"capability\",\"pid\":%d,\"tid\":%d,\"hardware_execute_slots\":%u,\"two_slots_available\":%s,\"mode\":\"%s\",\"library_sha256\":\"%s\"}\n",
        pid, tid, slots, slots >= 2 ? "true" : "false", append ? "append_test" : "inspect", kSha256);
    if (append) {
      trace->arm(pre, post);
      std::printf("{\"event\":\"armed\",\"seconds\":%u,\"pre_elf_va\":\"0x269d0\",\"post_elf_va\":\"0x269d4\"}\n", seconds);
      const auto deadline = Clock::now() + std::chrono::seconds(seconds);
      for (;;) {
        checkCancel();
        user_pt_regs regs{};
        const unsigned event = trace->event(regs);
        if (event == 1) {
          if (writeAttempted) throw Error("unexpected second pre-call trap");
          identity.verify();
          if (library.address(pid) != post) throw Error("library mapping changed before write");
          checkInstructions(memory->value, pre);
          if (regs.sp % 16 || regs.sp > static_cast<uint64_t>(INT64_MAX) - 0xb0 ||
              regs.regs[1] != 0xb6 || regs.regs[4] != 0xb6 || regs.regs[3] != 1000 ||
              regs.regs[2] != regs.sp + 0x70 || regs.regs[5] != regs.sp + 0x6c)
            throw Error("pre-call B6 register/frame guard failed; no write");
          uint8_t addValid = 0, clearValid = 0;
          uint64_t add = 0, clear = 0;
          readAt(memory->value, regs.sp + 0x90, &addValid, sizeof(addValid));
          readAt(memory->value, regs.sp + 0x98, &add, sizeof(add));
          readAt(memory->value, regs.sp + 0xa0, &clearValid, sizeof(clearValid));
          readAt(memory->value, regs.sp + 0xa8, &clear, sizeof(clear));
          std::printf("{\"event\":\"pre_request\",\"bds_add_valid\":%u,\"bds_add\":\"0x%016" PRIx64 "\",\"bds_clear_valid\":%u,\"bds_clear\":\"0x%016" PRIx64 "\"}\n",
              static_cast<unsigned>(addValid), add, static_cast<unsigned>(clearValid), clear);
          if (addValid != 1 || clearValid != 1 || add != kHigh31Add || clear != kHigh31Clear)
            throw Error("only the fixed BDS high31 add/complement-clear request is permitted; no write");
          if (Clock::now() + std::chrono::milliseconds(1500) >= deadline)
            throw Error("insufficient observation time for guarded request; no write");
          checkCancel();
          frame = regs.sp;
          writeAttempted = true; // A partial/failed pwrite must also be restored.
          stackRestore = "unknown";
          writeClearField(memory->value, frame, 0);
          uint64_t readback = UINT64_MAX;
          readAt(memory->value, frame + 0xa8, &readback, sizeof(readback));
          if (readback != 0) throw Error("zero BDS clear-mask readback failed");
          std::printf("{\"event\":\"fixed_clear_rewrite\",\"old_clear\":\"0x80000000ffffffff\",\"new_clear\":\"0x0\",\"verified\":true}\n");
          trace->disablePre();
          trace->resume(); // Execute the original BL without changing PC/GPRs.
        } else if (event == 2) {
          if (!writeAttempted || regs.sp != frame) throw Error("post-call frame does not match this experiment");
          uint32_t status = UINT32_MAX;
          readAt(memory->value, frame + 0x6c, &status, sizeof(status));
          observedPost = true;
          std::printf("{\"event\":\"qmi_response\",\"request_id\":182,\"indication_id\":182,\"client_status\":%u,\"modem_status\":%u}\n",
              static_cast<uint32_t>(regs.regs[0]), status);
          rc = 0;
          break;
        }
        if (Clock::now() >= deadline) throw Error("observation timed out");
        poll(nullptr, 0, 2);
      }
    } else rc = 0;
  } catch (const std::exception& error) {
    std::cout << "{\"event\":\"error\",\"message\":" << json(error.what()) << "}" << std::endl;
  }
  if (writeAttempted) {
    try {
      // Only touch the saved stack field when the same stopped frame is still
      // at the original call or return site. Never guess from a callee's SP.
      user_pt_regs regs{};
      if (!trace || !memory || !trace->prepareCleanup() || !trace->stoppedRegisters(regs) ||
          regs.sp != frame || (regs.pc != pre && regs.pc != post))
        throw Error("saved request frame cannot be confirmed; stack restoration is unknown");
      checkInstructions(memory->value, pre, true);
      writeClearField(memory->value, frame, kHigh31Clear);
      uint64_t readback = 0;
      rollbackRead(memory->value, frame + 0xa8, &readback, sizeof(readback));
      if (readback != kHigh31Clear) throw Error("original clear-mask restoration readback failed");
      stackRestore = "restored";
    } catch (const std::exception& error) {
      std::cout << "{\"event\":\"stack_restore_error\",\"message\":" << json(error.what()) << "}" << std::endl;
    }
  }
  std::printf("{\"event\":\"stack_restore\",\"state\":\"%s\",\"observed_post\":%s,\"host_must_restore_original_modem_list\":%s}\n",
      stackRestore, observedPost ? "true" : "false", writeAttempted ? "true" : "false");
  const bool clean = !trace || trace->close();
  std::printf("{\"event\":\"cleanup\",\"hardware_restored_and_detached\":%s}\n", clean ? "true" : "false");
  if (!clean || (writeAttempted && std::strcmp(stackRestore, "restored"))) return 2;
  return rc;
}
