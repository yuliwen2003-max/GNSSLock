// One-shot, read-only QMI response probe for one verified Qualcomm GNSS HAL.
// Only a per-thread hardware execute breakpoint is installed. No code, general
// registers, process memory, files, partitions, or security policy are changed.
#include <asm/ptrace.h>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <climits>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <iostream>
#include <memory>
#include <poll.h>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef __WALL
#define __WALL 0x40000000
#endif
#ifndef NT_ARM_HW_BREAK
#define NT_ARM_HW_BREAK 0x402
#endif

namespace {
using Clock = std::chrono::steady_clock;
constexpr char kLibrary[] = "/vendor/lib64/libloc_api_v02.so";
constexpr char kService[] = "android.hardware.gnss-aidl-service-qti";
constexpr char kThread[] = "LocApiMsgTask";
constexpr char kSha256[] = "e4d37489f1511535ee611ec67ccc3b7592a8c2f640b4978645c0356743471828";
struct ProbeKind {
  const char* name;
  uint64_t watchVa;
  uint32_t callInstruction, watchInstruction;
};
// These are the only permitted code sites; the CLI accepts no raw addresses.
constexpr ProbeKind kConstellation{"constellation", 0x4c684, 0x94004028, 0xf9401673};
constexpr ProbeKind kSatellite{"satellite", 0x269d4, 0x9400d754, 0xb9406fe8};
volatile sig_atomic_t cancelled = 0;
void onSignal(int value) { cancelled = value; }
using Error = std::runtime_error;
std::string sysError(const char* operation) { return std::string(operation) + ": " + std::strerror(errno); }
void checkCancel() { if (cancelled) throw Error("cancelled by signal " + std::to_string(cancelled)); }
struct Fd {
  int value;
  explicit Fd(int fd) : value(fd) {}
  ~Fd() { if (value >= 0) close(value); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
};
std::string json(const std::string& input) {
  std::string out = "\"";
  for (unsigned char c : input) {
    if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
    else if (c < 0x20) { char escaped[7]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c); out += escaped; }
    else out += static_cast<char>(c);
  }
  return out + '"';
}
std::string hex(uint64_t value) { std::ostringstream out; out << "0x" << std::hex << value; return out.str(); }
uint64_t number(const std::string& text, unsigned base, uint64_t max = UINT64_MAX) {
  if (text.empty()) throw Error("missing numeric argument");
  uint64_t value = 0;
  for (char c : text) {
    unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 255;
    if (d >= base || value > (max - d) / base) throw Error("invalid numeric argument");
    value = value * base + d;
  }
  return value;
}
std::string proc(pid_t id, const char* tail) { return "/proc/" + std::to_string(id) + "/" + tail; }
std::string readText(const std::string& path, size_t limit = 1024 * 1024) {
  Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
  if (fd.value < 0) throw Error(sysError("open proc file"));
  std::string text;
  char buf[4096];
  for (;;) {
    checkCancel();
    ssize_t n = read(fd.value, buf, sizeof(buf));
    if (n < 0 && errno == EINTR) continue;
    if (n < 0) throw Error(sysError("read proc file"));
    if (!n) return text;
    if (text.size() + static_cast<size_t>(n) > limit) throw Error("oversized proc file");
    text.append(buf, static_cast<size_t>(n));
  }
}
void readAt(int fd, uint64_t address, void* output, size_t count) {
  if (address > INT64_MAX || count > INT64_MAX - address) throw Error("read offset overflow");
  size_t done = 0;
  while (done < count) {
    checkCancel();
    ssize_t n = pread(fd, static_cast<char*>(output) + done, count - done, static_cast<off_t>(address + done));
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) throw Error(n < 0 ? sysError("read verified field") : "short read of verified field");
    done += static_cast<size_t>(n);
  }
}
uint64_t birth(pid_t id) {
  const auto text = readText(proc(id, "stat"), 8192);
  const auto end = text.rfind(')');
  if (end == std::string::npos) throw Error("invalid process stat");
  std::istringstream in(text.substr(end + 1));
  std::string value;
  for (unsigned field = 3; field <= 22; ++field) if (!(in >> value)) throw Error("truncated process stat");
  return number(value, 10);
}
struct Identity {
  pid_t pid, tid;
  uint64_t pidBirth, tidBirth;
  Identity(pid_t p, pid_t t) : pid(p), tid(t), pidBirth(birth(p)), tidBirth(birth(t)) { verify(); }
  void verify() const {
    if (birth(pid) != pidBirth || birth(tid) != tidBirth) throw Error("PID or TID was reused");
    char executable[PATH_MAX + 1];
    ssize_t n = readlink(proc(pid, "exe").c_str(), executable, PATH_MAX);
    if (n <= 0 || n == PATH_MAX) throw Error("cannot verify service executable");
    executable[n] = 0;
    const char* name = std::strrchr(executable, '/');
    if (!name || std::strcmp(name + 1, kService)) throw Error("unexpected service executable");
    std::istringstream status(readText(proc(tid, "status"), 16384));
    std::string line, threadName;
    pid_t tgid = 0;
    while (std::getline(status, line)) {
      if (line.rfind("Name:\t", 0) == 0) threadName = line.substr(6);
      if (line.rfind("Tgid:\t", 0) == 0) tgid = static_cast<pid_t>(number(line.substr(6), 10, INT_MAX));
    }
    if (tgid != pid || threadName != kThread) throw Error("TID must belong to PID and be LocApiMsgTask");
  }
};
std::string sha256(int fd) {
  int pipes[2];
  if (pipe2(pipes, O_CLOEXEC | O_NONBLOCK)) throw Error(sysError("hash pipe"));
  Fd input(pipes[0]), output(pipes[1]);
  const auto fdPath = proc(getpid(), ("fd/" + std::to_string(fd)).c_str());
  const pid_t child = fork();
  if (child < 0) throw Error(sysError("hash fork"));
  if (!child) {
    if (dup2(output.value, STDOUT_FILENO) < 0) _exit(126);
    int flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (flags < 0 || fcntl(STDOUT_FILENO, F_SETFL, flags & ~O_NONBLOCK) < 0) _exit(126);
    int nullFd = open("/dev/null", O_WRONLY);
    if (nullFd >= 0) dup2(nullFd, STDERR_FILENO);
    execl("/system/bin/toybox", "toybox", "sha256sum", fdPath.c_str(), static_cast<char*>(nullptr));
    _exit(127);
  }
  std::string result;
  int status = 0;
  bool reaped = false;
  try {
    const auto deadline = Clock::now() + std::chrono::seconds(5);
    for (;;) {
      checkCancel();
      char buf[512];
      ssize_t n;
      while ((n = read(input.value, buf, sizeof(buf))) > 0) {
        result.append(buf, static_cast<size_t>(n));
        if (result.size() > 1024) throw Error("oversized hash result");
      }
      if (n < 0 && errno != EAGAIN && errno != EINTR) throw Error(sysError("hash read"));
      if (reaped) break;
      const auto waited = waitpid(child, &status, WNOHANG);
      if (waited == child) { reaped = true; continue; }
      if (waited < 0 && errno != EINTR) throw Error(sysError("hash wait"));
      if (Clock::now() >= deadline) throw Error("hash timed out");
      poll(nullptr, 0, 5);
    }
  } catch (...) {
    if (!reaped) { kill(child, SIGKILL); while (waitpid(child, &status, 0) < 0 && errno == EINTR) {} }
    throw;
  }
  if (!WIFEXITED(status) || WEXITSTATUS(status) || result.size() < 66 || result.substr(0, 64) != kSha256 || result[64] != ' ')
    throw Error("library SHA256 verification failed");
  return result.substr(0, 64);
}
struct Library {
  Fd fd;
  struct stat original{};
  std::string path;
  const ProbeKind& kind;
  Library(pid_t pid, const ProbeKind& probeKind) : fd(open((proc(pid, "root") + kLibrary).c_str(), O_RDONLY | O_CLOEXEC)), path(proc(pid, "root") + kLibrary), kind(probeKind) {
    if (fd.value < 0 || fstat(fd.value, &original)) throw Error(sysError("open/stat library"));
    if (!S_ISREG(original.st_mode) || original.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) throw Error("invalid library file");
    sha256(fd.value);
    Elf64_Ehdr elf{};
    readAt(fd.value, 0, &elf, sizeof(elf));
    if (std::memcmp(elf.e_ident, ELFMAG, SELFMAG) || elf.e_ident[EI_CLASS] != ELFCLASS64 || elf.e_ident[EI_DATA] != ELFDATA2LSB || elf.e_machine != EM_AARCH64 || elf.e_type != ET_DYN || elf.e_phentsize != sizeof(Elf64_Phdr) || !elf.e_phnum || elf.e_phnum > 128 || elf.e_phoff > static_cast<uint64_t>(original.st_size) || elf.e_phnum * sizeof(Elf64_Phdr) > static_cast<uint64_t>(original.st_size) - elf.e_phoff)
      throw Error("unsupported ELF layout");
    bool executable = false;
    for (unsigned i = 0; i < elf.e_phnum; ++i) {
      Elf64_Phdr ph{};
      readAt(fd.value, elf.e_phoff + i * sizeof(ph), &ph, sizeof(ph));
      // This verified ELF uses identical file offsets and virtual addresses.
      if (ph.p_type == PT_LOAD && ph.p_vaddr != ph.p_offset) throw Error("unexpected ELF address layout");
      if (ph.p_type == PT_LOAD && (ph.p_flags & PF_X) && ph.p_vaddr <= kind.watchVa - 4 && ph.p_filesz >= kind.watchVa + 4 - ph.p_vaddr) executable = true;
    }
    uint32_t instructions[2]{};
    readAt(fd.value, kind.watchVa - 4, instructions, sizeof(instructions));
    if (!executable || instructions[0] != kind.callInstruction || instructions[1] != kind.watchInstruction) throw Error("unverified QMI call site");
  }
  uint64_t address(pid_t pid) const {
    struct stat current{}, named{};
    if (fstat(fd.value, &current) || stat(path.c_str(), &named) || current.st_dev != original.st_dev || current.st_ino != original.st_ino || current.st_size != original.st_size || current.st_mtim.tv_sec != original.st_mtim.tv_sec || current.st_mtim.tv_nsec != original.st_mtim.tv_nsec || current.st_ctim.tv_sec != original.st_ctim.tv_sec || current.st_ctim.tv_nsec != original.st_ctim.tv_nsec || named.st_dev != original.st_dev || named.st_ino != original.st_ino)
      throw Error("library identity changed");
    std::istringstream maps(readText(proc(pid, "maps"), 4 * 1024 * 1024));
    std::string line;
    uint64_t found = 0;
    while (std::getline(maps, line)) {
      std::istringstream fields(line);
      std::string range, permissions, offset, device, inode, mapPath;
      if (!(fields >> range >> permissions >> offset >> device >> inode)) throw Error("invalid maps line");
      std::getline(fields, mapPath);
      const auto startPath = mapPath.find_first_not_of(' ');
      if (startPath == std::string::npos || mapPath.substr(startPath) != kLibrary) continue;
      const auto dash = range.find('-'), colon = device.find(':');
      if (dash == std::string::npos || colon == std::string::npos || number(inode, 10) != original.st_ino || number(device.substr(0, colon), 16) != major(original.st_dev) || number(device.substr(colon + 1), 16) != minor(original.st_dev)) throw Error("library mapping identity mismatch");
      const uint64_t start = number(range.substr(0, dash), 16), end = number(range.substr(dash + 1), 16), off = number(offset, 16);
      if (start >= end) throw Error("invalid mapping range");
      if (off <= kind.watchVa - 4 && end - start >= kind.watchVa + 4 - off) {
        if (permissions != "r-xp" || start < off || kind.watchVa > UINT64_MAX - (start - off) || found) throw Error("ambiguous QMI execution mapping");
        found = start - off + kind.watchVa;
      }
    }
    if (!found || found % 4) throw Error("no verified QMI execution mapping");
    return found;
  }
};

class Trace {
 public:
  explicit Trace(pid_t tid) : tid_(tid) {
    if (ptrace(PTRACE_SEIZE, tid_, nullptr, nullptr)) throw Error(sysError("PTRACE_SEIZE"));
    attached_ = true;
  }
  ~Trace() { close(); }
  void stop() {
    if (ptrace(PTRACE_INTERRUPT, tid_, nullptr, nullptr)) throw Error(sysError("PTRACE_INTERRUPT"));
    const auto end = Clock::now() + std::chrono::seconds(1);
    while (!stopped_ && attached_) {
      checkCancel();
      collect();
      if (Clock::now() >= end) throw Error("thread stop timed out");
      if (!stopped_) poll(nullptr, 0, 1);
    }
    if (!attached_) throw Error("thread exited");
    if (signal_) throw Error("real signal arrived while attaching");
  }
  unsigned inspect() {
    iovec io{&original_, sizeof(original_)};
    if (ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io)) throw Error(sysError("hardware execute breakpoints unavailable"));
    const unsigned count = original_.dbg_info & 0xff;
    if (!count || count > 16 || io.iov_len < 8 + count * 16 || io.iov_len > sizeof(original_)) throw Error("unsupported hardware breakpoint regset");
    for (unsigned i = 0; i < count; ++i) if (original_.dbg_regs[i].ctrl & 1) throw Error("thread already has an enabled hardware breakpoint");
    haveOriginal_ = true;
    return count;
  }
  void arm(uint64_t address) {
    if (!haveOriginal_ || !stopped_) throw Error("hardware state was not inspected");
    auto state = original_;
    state.dbg_regs[0].addr = address;
    state.dbg_regs[0].ctrl = (0xfu << 5) | (2u << 1) | 1u; // four bytes, execute, EL0
    state.dbg_regs[0].pad = 0;
    breakAddress_ = address;
    dirty_ = true; // Restore even if SETREGSET reports a partial failure.
    iovec io{&state, 24}; // Only slot zero; all other slots remain untouched.
    if (ptrace(PTRACE_SETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io)) throw Error(sysError("hardware breakpoint installation failed"));
    user_hwdebug_state check{};
    io = {&check, sizeof(check)};
    if (ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io) || check.dbg_regs[0].addr != address || check.dbg_regs[0].ctrl != state.dbg_regs[0].ctrl) throw Error("hardware breakpoint verification failed");
    if (ptrace(PTRACE_CONT, tid_, nullptr, nullptr)) throw Error(sysError("PTRACE_CONT"));
    stopped_ = false;
  }
  // Returns 0 while running, 1 for our hardware trap, or 2 for another stop/exit.
  int pollEvent(uint64_t address, user_pt_regs& regs) {
    collect();
    if (!attached_) return 2;
    if (!stopped_) return 0;
    siginfo_t info{};
    if (signal_ != SIGTRAP || ptrace(PTRACE_GETSIGINFO, tid_, nullptr, &info) || info.si_code != TRAP_HWBKPT) return 2;
    iovec io{&regs, sizeof(regs)};
    if (ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_PRSTATUS), &io) || io.iov_len != sizeof(regs)) throw Error(sysError("read trap registers"));
    if (regs.pc != address) return 2;
    signal_ = 0; // Suppress only this tool's verified hardware trap.
    return 1;
  }
  bool close() noexcept {
    if (!attached_) return true;
    const auto end = Clock::now() + std::chrono::seconds(2);
    if (!stopped_) ptrace(PTRACE_INTERRUPT, tid_, nullptr, nullptr);
    do {
      if (!stopped_) { try { collect(); } catch (...) {} }
      if (!attached_) return true;
      if (stopped_) {
        // A hardware trap can race the deadline/cancellation and arrive before
        // PTRACE_INTERRUPT. Never reinject this tool's SIGTRAP into the service.
        if (dirty_ && signal_ == SIGTRAP && isOwnTrap()) signal_ = 0;
        if (dirty_) {
          iovec io{&original_, 24};
          const int setRc = ptrace(PTRACE_SETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io);
          const int setError = setRc == 0 ? 0 : errno;
          user_hwdebug_state check{};
          io = {&check, sizeof(check)};
          const int getRc = ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_ARM_HW_BREAK), &io);
          const int getError = getRc == 0 ? 0 : errno;
          // Linux may keep the last address and normalize the disabled control
          // word after perf_event_disable(). Disabled original slots need only
          // be confirmed disabled; their address cannot cause an execute trap.
          const bool restored = getRc == 0 && io.iov_len >= 24 &&
              ((original_.dbg_regs[0].ctrl & 1u) == 0 ?
               (check.dbg_regs[0].ctrl & 1u) == 0 :
               check.dbg_regs[0].addr == original_.dbg_regs[0].addr &&
               check.dbg_regs[0].ctrl == original_.dbg_regs[0].ctrl);
          std::printf("{\"event\":\"restore_breakpoint\",\"original_ctrl\":\"0x%" PRIx32
                      "\",\"original_addr\":\"0x%" PRIx64
                      "\",\"set_errno\":%d,\"get_errno\":%d,\"readback_valid\":%s,"
                      "\"readback_ctrl\":\"0x%" PRIx32 "\",\"readback_addr\":\"0x%" PRIx64
                      "\",\"accepted\":%s}\n",
                      static_cast<uint32_t>(original_.dbg_regs[0].ctrl),
                      static_cast<uint64_t>(original_.dbg_regs[0].addr), setError, getError,
                      getRc == 0 && io.iov_len >= 24 ? "true" : "false",
                      static_cast<uint32_t>(check.dbg_regs[0].ctrl),
                      static_cast<uint64_t>(check.dbg_regs[0].addr), restored ? "true" : "false");
          std::fflush(stdout);
          if (restored) dirty_ = false;
        }
        // Always attempt detach once restoration has been attempted. A failed
        // readback must not keep the HAL stopped until tracer-exit cleanup.
        // Kernel detach also removes this tracer's hardware breakpoints.
        if (ptrace(PTRACE_DETACH, tid_, nullptr, reinterpret_cast<void*>(static_cast<intptr_t>(signal_))) == 0) {
          attached_ = false;
          std::printf("{\"event\":\"detach\",\"success\":true,\"restore_confirmed\":%s}\n", dirty_ ? "false" : "true");
          std::fflush(stdout);
          return !dirty_;
        }
      }
      poll(nullptr, 0, 1);
    } while (Clock::now() < end);
    // No EXITKILL. Kernel tracer-exit cleanup detaches and removes ptrace HW traps.
    return false;
  }
 private:
  bool isOwnTrap() noexcept {
    siginfo_t info{};
    user_pt_regs regs{};
    iovec io{&regs, sizeof(regs)};
    return breakAddress_ &&
        ptrace(PTRACE_GETSIGINFO, tid_, nullptr, &info) == 0 && info.si_code == TRAP_HWBKPT &&
        ptrace(PTRACE_GETREGSET, tid_, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0 &&
        io.iov_len == sizeof(regs) && regs.pc == breakAddress_;
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
  bool attached_ = false, stopped_ = false, haveOriginal_ = false, dirty_ = false;
  int signal_ = 0;
  uint64_t breakAddress_ = 0;
  user_hwdebug_state original_{};
};
} // namespace

int main(int argc, char** argv) {
  std::unique_ptr<Trace> trace;
  int rc = 1;
  try {
    struct sigaction action{};
    action.sa_handler = onSignal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, nullptr) || sigaction(SIGTERM, &action, nullptr)) throw Error(sysError("signal handlers"));
    if (getuid() || geteuid()) throw Error("root is required");
    pid_t pid = 0, tid = 0;
    unsigned seconds = 10;
    bool watch = false, haveKind = false;
    const ProbeKind* kind = &kConstellation;
    for (int i = 1; i < argc; ++i) {
      const std::string option = argv[i];
      if (option == "--watch") { if (watch) throw Error("duplicate --watch"); watch = true; continue; }
      if (++i >= argc) throw Error("usage: gpslock-qmi-probe --pid PID --tid TID [--seconds 1..20] [--kind constellation|satellite] [--watch]");
      if (option == "--pid" && !pid) pid = static_cast<pid_t>(number(argv[i], 10, INT_MAX));
      else if (option == "--tid" && !tid) tid = static_cast<pid_t>(number(argv[i], 10, INT_MAX));
      else if (option == "--seconds") seconds = static_cast<unsigned>(number(argv[i], 10, 20));
      else if (option == "--kind" && !haveKind) {
        const std::string requested = argv[i];
        if (requested == "constellation") kind = &kConstellation;
        else if (requested == "satellite") kind = &kSatellite;
        else throw Error("--kind must be constellation or satellite");
        haveKind = true;
      }
      else throw Error("unknown or duplicate argument");
    }
    if (pid <= 1 || tid <= 1 || pid == getpid() || !seconds || seconds > 20) throw Error("explicit valid --pid and --tid, seconds 1..20 are required");
    const Identity identity(pid, tid);
    Library library(pid, *kind);
    const auto address = library.address(pid);
    Fd memory(open(proc(pid, "mem").c_str(), O_RDONLY | O_CLOEXEC));
    if (memory.value < 0) throw Error(sysError("open read-only process memory"));
    trace = std::make_unique<Trace>(tid);
    trace->stop();
    identity.verify();
    if (library.address(pid) != address) throw Error("library mappings changed before observation");
    uint32_t code[2]{};
    readAt(memory.value, address - 4, code, sizeof(code));
    if (code[0] != kind->callInstruction || code[1] != kind->watchInstruction) throw Error("live QMI call site differs from verified ELF");
    const auto slots = trace->inspect();
    std::cout << "{\"event\":\"capability\",\"pid\":" << pid << ",\"tid\":" << tid << ",\"pid_starttime\":" << identity.pidBirth << ",\"thread_starttime\":" << identity.tidBirth << ",\"hardware_execute_slots\":" << slots << ",\"library_sha256\":\"" << kSha256 << "\",\"kind\":" << json(kind->name) << ",\"watch_elf_va\":" << json(hex(kind->watchVa)) << ",\"mode\":\"" << (watch ? "one_shot" : "inspect") << "\"}" << std::endl;
    if (watch) {
      trace->arm(address);
      std::cout << "{\"event\":\"armed\",\"seconds\":" << seconds << "}" << std::endl;
      const auto deadline = Clock::now() + std::chrono::seconds(seconds);
      for (;;) {
        checkCancel();
        user_pt_regs regs{};
        int event = trace->pollEvent(address, regs);
        if (event == 2) throw Error("thread exited or another signal interrupted observation");
        if (event == 1) {
          identity.verify();
          if (regs.sp % 16 || regs.sp > UINT64_MAX - 0xb0) throw Error("unexpected QMI stack pointer");
          // Read only each mode's fixed, verified non-location response fields.
          if (kind == &kSatellite) {
            uint32_t modemStatus = 0;
            uint8_t blacklistValid = 0, clearValid = 0;
            uint64_t blacklistMask = 0, clearMask = 0;
            readAt(memory.value, regs.sp + 0x6c, &modemStatus, sizeof(modemStatus));
            readAt(memory.value, regs.sp + 0x90, &blacklistValid, sizeof(blacklistValid));
            readAt(memory.value, regs.sp + 0x98, &blacklistMask, sizeof(blacklistMask));
            readAt(memory.value, regs.sp + 0xa0, &clearValid, sizeof(clearValid));
            readAt(memory.value, regs.sp + 0xa8, &clearMask, sizeof(clearMask));
            std::cout << "{\"event\":\"qmi_response\",\"kind\":\"satellite\",\"request_id\":182,\"indication_id\":182,\"client_status\":" << static_cast<uint32_t>(regs.regs[0]) << ",\"modem_status\":" << modemStatus << ",\"bds_blacklist_valid\":" << static_cast<unsigned>(blacklistValid) << ",\"bds_blacklist_mask\":" << json(hex(blacklistMask)) << ",\"bds_clear_valid\":" << static_cast<unsigned>(clearValid) << ",\"bds_clear_mask\":" << json(hex(clearMask)) << "}" << std::endl;
          } else {
            uint32_t modemStatus = 0;
            uint8_t reset = 0, enableValid = 0, disableValid = 0;
            uint64_t enableMask = 0, disableMask = 0;
            readAt(memory.value, regs.sp + 0x2c, &modemStatus, sizeof(modemStatus));
            readAt(memory.value, regs.sp + 0x30, &reset, sizeof(reset));
            readAt(memory.value, regs.sp + 0x31, &enableValid, sizeof(enableValid));
            readAt(memory.value, regs.sp + 0x38, &enableMask, sizeof(enableMask));
            readAt(memory.value, regs.sp + 0x40, &disableValid, sizeof(disableValid));
            readAt(memory.value, regs.sp + 0x48, &disableMask, sizeof(disableMask));
            std::cout << "{\"event\":\"qmi_response\",\"request_id\":184,\"indication_id\":184,\"transport_status\":" << static_cast<uint32_t>(regs.regs[0]) << ",\"modem_status\":" << modemStatus << ",\"reset_constellations\":" << static_cast<unsigned>(reset) << ",\"enable_mask_valid\":" << static_cast<unsigned>(enableValid) << ",\"enable_mask\":" << json(hex(enableMask)) << ",\"disable_mask_valid\":" << static_cast<unsigned>(disableValid) << ",\"disable_mask\":" << json(hex(disableMask)) << "}" << std::endl;
          }
          break;
        }
        if (Clock::now() >= deadline) { std::cout << "{\"event\":\"timeout\"}" << std::endl; break; }
        poll(nullptr, 0, 2);
      }
    }
    rc = 0;
  } catch (const std::exception& error) {
    std::cout << "{\"event\":\"error\",\"message\":" << json(error.what()) << "}" << std::endl;
  }
  const bool clean = !trace || trace->close();
  std::cout << "{\"event\":\"cleanup\",\"hardware_state_restored_and_detached\":" << (clean ? "true" : "false") << "}" << std::endl;
  return clean ? rc : 2;
}
