// Temporary, process-memory-only workaround for one verified Qualcomm HAL.
// Build with an Android arm64 NDK C++17 compiler. This never edits the ELF file.
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <climits>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <limits>
#include <memory>
#include <poll.h>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#ifndef __WALL
#define __WALL 0x40000000
#endif

namespace {
constexpr char kLibrary[] = "/vendor/lib64/libloc_api_v02.so";
constexpr char kService[] = "android.hardware.gnss-aidl-service-qti";
constexpr char kSha256[] =
    "e4d37489f1511535ee611ec67ccc3b7592a8c2f640b4978645c0356743471828";
struct PatchDefinition {
  const char* kind;
  uint64_t instructionVa, instructionFileOffset;
  uint32_t stock, enabled, adjacent;
};
constexpr PatchDefinition kConstellationPatch = {
    "constellation", 0x4c728, 0x4c728,
    0x52800429, 0x52800469, 0x8a090109}; // mov w9, #0x21 -> #0x23.
constexpr PatchDefinition kSatellitePatch = {
    "satellite", 0x265fc, 0x265fc,
    0x54000141, 0x1400000a, 0xf9400c29}; // b.ne -> b 0x26624.
struct InstructionPair { uint32_t word, adjacent; };
static_assert(sizeof(InstructionPair) == 8, "instruction pair must be eight bytes");
volatile sig_atomic_t gCancelled = 0;
using Clock = std::chrono::steady_clock;

void onSignal(int signal) { gCancelled = signal; }

class Error : public std::runtime_error {
 public:
  explicit Error(const std::string& message) : std::runtime_error(message) {}
};

std::string errorText(const std::string& operation, int error = errno) {
  return operation + ": " + std::strerror(error);
}

void checkCancelled() {
  if (gCancelled) throw Error("cancelled by signal " + std::to_string(gCancelled));
}

class Fd {
 public:
  explicit Fd(int fd = -1) : fd_(fd) {}
  ~Fd() { if (fd_ >= 0) close(fd_); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
  int get() const { return fd_; }
 private:
  int fd_;
};

std::string readText(const std::string& path, size_t limit = 1024 * 1024) {
  Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
  if (fd.get() < 0) throw Error(errorText("open " + path));
  std::string text;
  char buffer[4096];
  for (;;) {
    checkCancelled();
    ssize_t count = read(fd.get(), buffer, sizeof(buffer));
    if (count < 0 && errno == EINTR) continue;
    if (count < 0) throw Error(errorText("read " + path));
    if (!count) return text;
    if (text.size() + static_cast<size_t>(count) > limit)
      throw Error("oversized file: " + path);
    text.append(buffer, static_cast<size_t>(count));
  }
}

void preadExact(int fd, void* output, size_t length, uint64_t offset,
                const char* what) {
  if (offset > static_cast<uint64_t>(std::numeric_limits<off_t>::max()) ||
      length > static_cast<uint64_t>(std::numeric_limits<off_t>::max()) - offset)
    throw Error(std::string(what) + ": offset overflow");
  size_t done = 0;
  while (done != length) {
    checkCancelled();
    ssize_t count = pread(fd, static_cast<char*>(output) + done, length - done,
                          static_cast<off_t>(offset + done));
    if (count < 0 && errno == EINTR) continue;
    if (count < 0) throw Error(errorText(what));
    if (!count) throw Error(std::string(what) + ": short read");
    done += static_cast<size_t>(count);
  }
}

uint64_t addChecked(uint64_t a, uint64_t b) {
  if (b > UINT64_MAX - a) throw Error("ELF or mapping address overflow");
  return a + b;
}

uint64_t alignDown(uint64_t n, uint64_t page) { return n - n % page; }
uint64_t alignUp(uint64_t n, uint64_t page) {
  return alignDown(addChecked(n, page - 1), page);
}

std::string procPath(pid_t pid, const char* tail) {
  return "/proc/" + std::to_string(pid) + "/" + tail;
}

uint64_t decimal(const std::string& value, uint64_t maximum, const char* what) {
  if (value.empty()) throw Error(std::string("empty ") + what);
  uint64_t result = 0;
  for (char c : value) {
    if (c < '0' || c > '9' || result > (maximum - (c - '0')) / 10)
      throw Error(std::string("invalid ") + what);
    result = result * 10 + static_cast<unsigned>(c - '0');
  }
  return result;
}

uint64_t hexadecimal(const std::string& value, const char* what) {
  if (value.empty()) throw Error(std::string("empty ") + what);
  uint64_t result = 0;
  for (char c : value) {
    unsigned digit;
    if (c >= '0' && c <= '9') digit = c - '0';
    else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
    else throw Error(std::string("invalid ") + what);
    if (result > (UINT64_MAX - digit) / 16)
      throw Error(std::string("overflow in ") + what);
    result = result * 16 + digit;
  }
  return result;
}

struct Identity { uint64_t startTime; };

Identity identify(pid_t pid) {
  char executable[PATH_MAX + 1];
  const auto path = procPath(pid, "exe");
  ssize_t count = readlink(path.c_str(), executable, PATH_MAX);
  if (count < 0) throw Error(errorText("readlink service executable"));
  if (!count || count == PATH_MAX) throw Error("invalid service executable link");
  executable[count] = '\0';
  const char* slash = std::strrchr(executable, '/');
  if (!slash || std::strcmp(slash + 1, kService) != 0)
    throw Error("PID is not the expected Qualcomm GNSS service");
  const auto stat = readText(procPath(pid, "stat"), 8192);
  const auto endName = stat.rfind(')');
  if (endName == std::string::npos) throw Error("invalid process stat");
  std::istringstream fields(stat.substr(endName + 1));
  std::string value;
  // The first token after comm is field 3; starttime is field 22.
  for (unsigned field = 3; field <= 22; ++field) {
    if (!(fields >> value)) throw Error("truncated process stat");
  }
  return {decimal(value, UINT64_MAX, "process starttime")};
}

void verifyIdentity(pid_t pid, const Identity& identity) {
  if (identify(pid).startTime != identity.startTime)
    throw Error("GNSS service restarted; refusing stale PID");
}

bool sameFile(const struct stat& a, const struct stat& b) {
  return a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
      a.st_size == b.st_size && a.st_mtim.tv_sec == b.st_mtim.tv_sec &&
      a.st_mtim.tv_nsec == b.st_mtim.tv_nsec &&
      a.st_ctim.tv_sec == b.st_ctim.tv_sec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec;
}

std::string sha256File(int libraryFd) {
  // Hash the already-open inode, even if its pathname is replaced concurrently.
  const std::string path = "/proc/" + std::to_string(getpid()) + "/fd/" +
      std::to_string(libraryFd);
  int pipeFds[2];
  if (pipe2(pipeFds, O_CLOEXEC | O_NONBLOCK) != 0)
    throw Error(errorText("pipe for sha256sum"));
  Fd output(pipeFds[0]);
  Fd input(pipeFds[1]);
  pid_t child = fork();
  if (child < 0) throw Error(errorText("fork sha256sum"));
  if (!child) {
    if (dup2(input.get(), STDOUT_FILENO) < 0) _exit(126);
    int flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (flags < 0 || fcntl(STDOUT_FILENO, F_SETFL, flags & ~O_NONBLOCK) < 0)
      _exit(126);
    int nullFd = open("/dev/null", O_WRONLY);
    if (nullFd >= 0) dup2(nullFd, STDERR_FILENO);
    execl("/system/bin/toybox", "toybox", "sha256sum", path.c_str(),
          static_cast<char*>(nullptr));
    _exit(127);
  }
  const auto deadline = Clock::now() + std::chrono::seconds(5);
  std::string result;
  int status = 0;
  bool reaped = false;
  try {
    for (;;) {
      checkCancelled();
      char buffer[512];
      for (;;) {
        ssize_t count = read(output.get(), buffer, sizeof(buffer));
        if (count > 0) {
          result.append(buffer, static_cast<size_t>(count));
          if (result.size() > 4096) throw Error("unexpected sha256sum output size");
          continue;
        }
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
          throw Error(errorText("read sha256sum output"));
        break;
      }
      const pid_t waited = waitpid(child, &status, WNOHANG);
      if (waited == child) { reaped = true; break; }
      if (waited < 0 && errno != EINTR) throw Error(errorText("wait sha256sum"));
      if (Clock::now() >= deadline) throw Error("sha256sum timed out");
      struct pollfd pfd = {output.get(), POLLIN, 0};
      poll(&pfd, 1, 5);
    }
    // The child has exited, so all of its pipe writes are now available.
    char buffer[512];
    for (;;) {
      ssize_t count = read(output.get(), buffer, sizeof(buffer));
      if (count > 0) {
        result.append(buffer, static_cast<size_t>(count));
        if (result.size() > 4096) throw Error("unexpected sha256sum output size");
      } else if (count < 0 && errno == EINTR) continue;
      else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
        throw Error(errorText("read sha256sum output"));
      else break;
    }
  } catch (...) {
    if (!reaped) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    }
    throw;
  }
  if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
    throw Error("toybox sha256sum failed");
  if (result.size() < 66 || (result[64] != ' ' && result[64] != '\t'))
    throw Error("malformed sha256sum output");
  std::string hash;
  for (size_t i = 0; i < 64; ++i) {
    char c = result[i];
    if (c >= 'A' && c <= 'F') c = static_cast<char>(c - 'A' + 'a');
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
      throw Error("malformed sha256sum digest");
    hash.push_back(c);
  }
  // A second digest or embedded newline is not a valid single-file result.
  const auto newline = result.find('\n');
  if (newline != result.size() - 1 || result.find('\r') != std::string::npos)
    throw Error("malformed sha256sum result line");
  return hash;
}

struct Mapping {
  uint64_t start, end, offset, inode, majorDevice, minorDevice;
  std::string permissions, path;
};

std::vector<Mapping> readMaps(pid_t pid) {
  std::istringstream input(readText(procPath(pid, "maps"), 4 * 1024 * 1024));
  std::vector<Mapping> result;
  std::string line;
  uint64_t previousEnd = 0;
  while (std::getline(input, line)) {
    std::istringstream fields(line);
    std::string range, offset, device, inode;
    Mapping map{};
    if (!(fields >> range >> map.permissions >> offset >> device >> inode))
      throw Error("malformed process maps");
    const auto dash = range.find('-');
    const auto colon = device.find(':');
    if (dash == std::string::npos || colon == std::string::npos ||
        map.permissions.size() != 4)
      throw Error("malformed process map fields");
    map.start = hexadecimal(range.substr(0, dash), "mapping start");
    map.end = hexadecimal(range.substr(dash + 1), "mapping end");
    map.offset = hexadecimal(offset, "mapping offset");
    map.majorDevice = hexadecimal(device.substr(0, colon), "mapping device major");
    map.minorDevice = hexadecimal(device.substr(colon + 1), "mapping device minor");
    map.inode = decimal(inode, UINT64_MAX, "mapping inode");
    std::getline(fields, map.path);
    const auto first = map.path.find_first_not_of(' ');
    map.path = first == std::string::npos ? "" : map.path.substr(first);
    if (map.start >= map.end || map.start < previousEnd)
      throw Error("overlapping or unordered process maps");
    previousEnd = map.end;
    result.push_back(map);
  }
  return result;
}

struct Library {
  explicit Library(pid_t pid, const PatchDefinition& definition)
      : patch(definition), path(procPath(pid, "root") + kLibrary),
        fd(open(path.c_str(), O_RDONLY | O_CLOEXEC)) {
    if (fd.get() < 0) throw Error(errorText("open target library"));
    if (fstat(fd.get(), &fileStat) != 0) throw Error(errorText("stat target library"));
    if (!S_ISREG(fileStat.st_mode) || fileStat.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr)))
      throw Error("target library is not a regular ELF file");
    hash = sha256File(fd.get());
    if (hash != kSha256) throw Error("unsupported library SHA256: " + hash);
    checkFile();
    Elf64_Ehdr header{};
    preadExact(fd.get(), &header, sizeof(header), 0, "read ELF header");
    if (std::memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
        header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB ||
        header.e_ident[EI_VERSION] != EV_CURRENT ||
        header.e_machine != EM_AARCH64 || header.e_type != ET_DYN ||
        header.e_version != EV_CURRENT || header.e_ehsize != sizeof(header) ||
        header.e_phentsize != sizeof(Elf64_Phdr) || !header.e_phnum ||
        header.e_phnum > 128 ||
        addChecked(header.e_phoff, header.e_phnum * sizeof(Elf64_Phdr)) >
            static_cast<uint64_t>(fileStat.st_size))
      throw Error("unsupported ELF layout");
    for (unsigned i = 0; i < header.e_phnum; ++i) {
      Elf64_Phdr ph{};
      preadExact(fd.get(), &ph, sizeof(ph),
                 header.e_phoff + i * sizeof(ph), "read ELF program header");
      if (ph.p_type != PT_LOAD) continue;
      if (ph.p_filesz > ph.p_memsz ||
          addChecked(ph.p_offset, ph.p_filesz) > static_cast<uint64_t>(fileStat.st_size))
        throw Error("invalid ELF load segment");
      segments.push_back(ph);
    }
    if (segments.empty()) throw Error("ELF has no load segments");
    InstructionPair instruction{};
    preadExact(fd.get(), &instruction, sizeof(instruction), patch.instructionFileOffset,
               "read ELF instruction");
    if (instruction.word != patch.stock || instruction.adjacent != patch.adjacent)
      throw Error("unexpected target or adjacent instruction in library file");
  }

  void checkFile() const {
    struct stat current{}, named{};
    if (fstat(fd.get(), &current) != 0 || stat(path.c_str(), &named) != 0)
      throw Error(errorText("recheck target library"));
    if (!sameFile(fileStat, current) || !sameFile(fileStat, named))
      throw Error("library file changed during operation");
  }

  uint64_t locate(pid_t pid) const {
    const auto maps = readMaps(pid);
    const long pageResult = sysconf(_SC_PAGESIZE);
    if (pageResult <= 0) throw Error("cannot determine kernel page size");
    const auto page = static_cast<uint64_t>(pageResult);
    std::vector<Mapping> relevant;
    std::set<uint64_t> commonBias;
    bool first = true;
    bool targetInElf = false;
    for (const auto& ph : segments) {
      if (ph.p_vaddr % page != ph.p_offset % page)
        throw Error("ELF segments do not match kernel page size");
      if (patch.instructionVa >= ph.p_vaddr &&
          addChecked(patch.instructionVa, sizeof(InstructionPair)) <=
              addChecked(ph.p_vaddr, ph.p_filesz) &&
          (ph.p_flags & PF_X) &&
          addChecked(ph.p_offset, patch.instructionVa - ph.p_vaddr) ==
              patch.instructionFileOffset)
        targetInElf = true;
    }
    if (!targetInElf) throw Error("target instruction is not in an executable ELF segment");
    for (const auto& map : maps) {
      const bool sameInode = map.inode == static_cast<uint64_t>(fileStat.st_ino) &&
          map.majorDevice == static_cast<uint64_t>(major(fileStat.st_dev)) &&
          map.minorDevice == static_cast<uint64_t>(minor(fileStat.st_dev));
      if (map.path != kLibrary && !sameInode) continue;
      if (map.path != kLibrary || !sameInode)
        throw Error("unexpected library pathname, device, or inode in maps");
      if (map.permissions[3] != 'p' ||
          (map.permissions[0] != 'r' && map.permissions[0] != '-') ||
          (map.permissions[1] != 'w' && map.permissions[1] != '-') ||
          (map.permissions[2] != 'x' && map.permissions[2] != '-') ||
          (map.permissions[1] == 'w' && map.permissions[2] == 'x') ||
          map.start % page || map.end % page || map.offset % page)
        throw Error("unexpected library mapping permissions or alignment");
      std::set<uint64_t> candidates;
      for (const auto& ph : segments) {
        if (!ph.p_filesz) continue;
        const auto offsetStart = alignDown(ph.p_offset, page);
        const auto offsetEnd = alignUp(addChecked(ph.p_offset, ph.p_filesz), page);
        if (map.offset < offsetStart || map.offset >= offsetEnd ||
            map.end - map.start > offsetEnd - map.offset) continue;
        if ((map.permissions[0] == 'r' && !(ph.p_flags & PF_R)) ||
            (map.permissions[1] == 'w' && !(ph.p_flags & PF_W)) ||
            (map.permissions[2] == 'x' && !(ph.p_flags & PF_X))) continue;
        const auto relative = addChecked(alignDown(ph.p_vaddr, page), map.offset - offsetStart);
        if (map.start < relative) continue;
        candidates.insert(map.start - relative);
      }
      if (candidates.empty()) throw Error("library map does not match ELF load headers");
      if (first) { commonBias = candidates; first = false; }
      else {
        std::set<uint64_t> intersection;
        std::set_intersection(commonBias.begin(), commonBias.end(),
                              candidates.begin(), candidates.end(),
                              std::inserter(intersection, intersection.end()));
        commonBias.swap(intersection);
      }
      relevant.push_back(map);
    }
    if (commonBias.size() != 1 || relevant.empty())
      throw Error("library does not have one unambiguous ELF load bias");
    const uint64_t bias = *commonBias.begin();
    for (const auto& ph : segments) {
      if (!ph.p_filesz) continue;
      uint64_t cursor = addChecked(bias, alignDown(ph.p_vaddr, page));
      const auto end = addChecked(bias, alignUp(addChecked(ph.p_vaddr, ph.p_filesz), page));
      for (const auto& map : relevant) {
        if (map.end <= cursor || map.start > cursor) continue;
        const auto expectedOffset = addChecked(alignDown(ph.p_offset, page),
            cursor - addChecked(bias, alignDown(ph.p_vaddr, page)));
        if (addChecked(map.offset, cursor - map.start) != expectedOffset) continue;
        cursor = std::min(map.end, end);
        if (cursor == end) break;
      }
      if (cursor != end) throw Error("ELF load segment has missing or unexpected mappings");
    }
    const auto address = addChecked(bias, patch.instructionVa);
    bool executable = false;
    for (const auto& map : relevant)
      if (address >= map.start && addChecked(address, sizeof(InstructionPair)) <= map.end &&
          map.permissions == "r-xp" &&
          addChecked(map.offset, address - map.start) == patch.instructionFileOffset)
        executable = true;
    if (!executable) throw Error("target instruction is not in the expected private r-x mapping");
    return bias;
  }

  const PatchDefinition& patch;
  std::string path, hash;
  Fd fd;
  struct stat fileStat{};
  std::vector<Elf64_Phdr> segments;
};

std::set<pid_t> taskIds(pid_t pid) {
  const auto path = procPath(pid, "task");
  DIR* raw = opendir(path.c_str());
  if (!raw) throw Error(errorText("enumerate GNSS threads"));
  std::unique_ptr<DIR, int(*)(DIR*)> directory(raw, closedir);
  std::set<pid_t> result;
  for (;;) {
    errno = 0;
    dirent* entry = readdir(directory.get());
    if (!entry) {
      if (errno) throw Error(errorText("read GNSS thread directory"));
      break;
    }
    if (entry->d_name[0] == '.') continue;
    const auto id = decimal(entry->d_name, INT_MAX, "thread ID");
    if (!id) throw Error("invalid zero thread ID");
    result.insert(static_cast<pid_t>(id));
  }
  if (result.empty()) throw Error("GNSS process has no threads");
  return result;
}

struct TracedThread {
  pid_t tid;
  bool stopped = false;
  bool released = false;
  int resumeSignal = 0;
};

class ThreadStopper {
 public:
  explicit ThreadStopper(pid_t pid)
      : pid_(pid), deadline_(Clock::now() + std::chrono::seconds(5)) {}
  ~ThreadStopper() { release(); }
  ThreadStopper(const ThreadStopper&) = delete;
  ThreadStopper& operator=(const ThreadStopper&) = delete;

  void stopAll() {
    // Reserve the final second of the five-second budget for failure cleanup.
    const auto stopDeadline = deadline_ - std::chrono::seconds(1);
    for (;;) {
      checkCancelled();
      if (Clock::now() >= stopDeadline) throw Error("timed out stopping all GNSS threads");
      const auto ids = taskIds(pid_);
      for (pid_t tid : ids) {
        auto found = std::find_if(threads_.begin(), threads_.end(),
            [tid](const TracedThread& thread) { return thread.tid == tid && !thread.released; });
        if (found != threads_.end()) continue;
        checkCancelled();
        if (ptrace(PTRACE_SEIZE, tid, nullptr, nullptr) != 0) {
          if (errno == ESRCH) continue; // Exited before seizure; rescan below.
          throw Error(errorText("PTRACE_SEIZE tid " + std::to_string(tid)));
        }
        threads_.push_back({tid});
        if (ptrace(PTRACE_INTERRUPT, tid, nullptr, nullptr) != 0 && errno != ESRCH)
          throw Error(errorText("PTRACE_INTERRUPT tid " + std::to_string(tid)));
      }
      for (;;) {
        checkCancelled();
        bool pending = false;
        for (auto& thread : threads_) {
          if (!thread.released && !thread.stopped) collectStop(thread);
          if (!thread.released && !thread.stopped) pending = true;
        }
        if (!pending) break;
        if (Clock::now() >= stopDeadline)
          throw Error("timed out waiting for GNSS thread stops");
        usleep(1000);
      }
      const auto after = taskIds(pid_);
      bool allStopped = true;
      for (pid_t tid : after) {
        const auto found = std::find_if(threads_.begin(), threads_.end(),
            [tid](const TracedThread& thread) {
              return thread.tid == tid && thread.stopped && !thread.released;
            });
        if (found == threads_.end()) { allStopped = false; break; }
      }
      if (allStopped) {
        // A second enumeration closes the attach/clone race before memory access.
        if (taskIds(pid_) == after) return;
      }
    }
  }

  bool release() noexcept {
    bool pending;
    do {
      pending = false;
      for (auto& thread : threads_) {
        if (thread.released) continue;
        if (!thread.stopped) {
          // A tracee must be in a ptrace stop for PTRACE_DETACH to work.
          ptrace(PTRACE_INTERRUPT, thread.tid, nullptr, nullptr);
          try { collectStop(thread); } catch (...) {}
        }
        if (thread.released) continue;
        if (thread.stopped) {
          int rc;
          do {
            rc = ptrace(PTRACE_DETACH, thread.tid, nullptr,
                        reinterpret_cast<void*>(static_cast<intptr_t>(thread.resumeSignal)));
          } while (rc != 0 && errno == EINTR);
          if (rc == 0 || errno == ESRCH) {
            thread.released = true;
            continue;
          }
        }
        pending = true;
      }
      if (pending && Clock::now() < deadline_) usleep(1000);
    } while (pending && Clock::now() < deadline_);
    // No EXITKILL is used: kernel tracer-exit cleanup detaches a tracee that is
    // still in an uninterruptible kernel wait when the bounded cleanup expires.
    return !pending;
  }

  std::string unreleased() const {
    std::string ids;
    for (const auto& thread : threads_) {
      if (thread.released) continue;
      if (!ids.empty()) ids += ',';
      ids += std::to_string(thread.tid);
    }
    return ids;
  }

 private:
  void collectStop(TracedThread& thread) {
    int status = 0;
    const auto waited = waitpid(thread.tid, &status, __WALL | WNOHANG);
    if (!waited || (waited < 0 && errno == EINTR)) return;
    if (waited < 0) {
      if (errno == ECHILD || errno == ESRCH) {
        const auto path = "/proc/" + std::to_string(thread.tid);
        if (access(path.c_str(), F_OK) != 0 && errno == ENOENT) {
          thread.released = true;
          return;
        }
      }
      throw Error(errorText("waitpid tid " + std::to_string(thread.tid)));
    }
    if (WIFEXITED(status) || WIFSIGNALED(status)) {
      thread.released = true;
      return;
    }
    if (!WIFSTOPPED(status)) throw Error("unexpected ptrace wait status");
    thread.stopped = true;
    const unsigned event = static_cast<unsigned>(status) >> 16;
    const int signal = WSTOPSIG(status);
    // Suppress only PTRACE_INTERRUPT's synthetic SIGTRAP. Preserve real signals
    // and job-control stops that arrived while attaching.
    thread.resumeSignal = event == PTRACE_EVENT_STOP && signal == SIGTRAP ? 0 : signal;
  }

  pid_t pid_;
  Clock::time_point deadline_;
  std::vector<TracedThread> threads_;
};

InstructionPair readInstructions(int fd, uint64_t address, bool ignoreCancellation = false) {
  InstructionPair instructions{};
  if (!ignoreCancellation) {
    preadExact(fd, &instructions, sizeof(instructions), address, "read target instructions");
    return instructions;
  }
  // Rollback must still run after cancellation.
  ssize_t count;
  do { count = pread(fd, &instructions, sizeof(instructions), static_cast<off_t>(address)); }
  while (count < 0 && errno == EINTR);
  if (count < 0) throw Error(errorText("read back rollback instruction"));
  if (count != sizeof(instructions)) throw Error("short rollback verification read");
  return instructions;
}

void writeWord(int fd, uint64_t address, uint32_t word) {
  // Deliberately one aligned four-byte write. On arm64 the kernel's proc-mem
  // access_process_vm/copy_to_user_page path maintains instruction-cache coherency.
  ssize_t count;
  do { count = pwrite(fd, &word, sizeof(word), static_cast<off_t>(address)); }
  while (count < 0 && errno == EINTR);
  if (count < 0) throw Error(errorText("write target instruction"));
  if (count != sizeof(word)) throw Error("short target instruction write");
}

const char* stateFor(uint32_t word, const PatchDefinition& patch) {
  return word == patch.stock ? "stock" : word == patch.enabled ? "enabled" : "unknown";
}

std::string hex(uint64_t value, unsigned width = 0) {
  std::ostringstream output;
  output << "0x" << std::hex << std::setw(width) << std::setfill('0') << value;
  return output.str();
}

std::string json(const std::string& value) {
  std::ostringstream output;
  output << '"';
  for (unsigned char c : value) {
    switch (c) {
      case '"': output << "\\\""; break;
      case '\\': output << "\\\\"; break;
      case '\b': output << "\\b"; break;
      case '\f': output << "\\f"; break;
      case '\n': output << "\\n"; break;
      case '\r': output << "\\r"; break;
      case '\t': output << "\\t"; break;
      default:
        if (c < 0x20)
          output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                 << static_cast<unsigned>(c) << std::dec;
        else output << static_cast<char>(c);
    }
  }
  output << '"';
  return output.str();
}
} // namespace

int main(int argc, char** argv) {
  std::string command, hash, failure, rollback = "not_needed", detach = "not_needed";
  pid_t pid = 0;
  uint64_t bias = 0, address = 0;
  uint32_t word = 0, adjacent = 0;
  bool haveLocation = false, haveWord = false, changed = false;
  const PatchDefinition* patch = nullptr;
  std::unique_ptr<ThreadStopper> stopped;
  try {
    struct sigaction action{};
    action.sa_handler = onSignal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, nullptr) != 0 ||
        sigaction(SIGTERM, &action, nullptr) != 0)
      throw Error(errorText("install cancellation handlers"));
    if (getuid() != 0 || geteuid() != 0) throw Error("root is required");
    if (argc != 3)
      throw Error("usage: gpslock-hal status|enable|disable|sv-status|sv-enable|sv-disable PID");
    command = argv[1];
    if (command == "status" || command == "enable" || command == "disable")
      patch = &kConstellationPatch;
    else if (command == "sv-status" || command == "sv-enable" || command == "sv-disable")
      patch = &kSatellitePatch;
    else
      throw Error("unsupported command");
    const bool statusOnly = command == "status" || command == "sv-status";
    const bool enable = command == "enable" || command == "sv-enable";
    pid = static_cast<pid_t>(decimal(argv[2], INT_MAX, "PID"));
    if (pid <= 1 || pid == getpid()) throw Error("invalid target PID");
    const Identity identity = identify(pid);
    Library library(pid, *patch);
    hash = library.hash;
    bias = library.locate(pid);
    address = addChecked(bias, patch->instructionVa);
    if (address % sizeof(uint32_t)) throw Error("unaligned target instruction");
    haveLocation = true;
    verifyIdentity(pid, identity);
    Fd memory(open(procPath(pid, "mem").c_str(),
                   (statusOnly ? O_RDONLY : O_RDWR) | O_CLOEXEC));
    if (memory.get() < 0) throw Error(errorText("open GNSS process memory"));
    if (!statusOnly) {
      stopped.reset(new ThreadStopper(pid));
      stopped->stopAll();
      verifyIdentity(pid, identity);
      library.checkFile();
      if (library.locate(pid) != bias) throw Error("library mappings changed before patch");
    }
    const InstructionPair before = readInstructions(memory.get(), address);
    word = before.word;
    adjacent = before.adjacent;
    haveWord = true;
    if ((word != patch->stock && word != patch->enabled) || adjacent != patch->adjacent)
      throw Error("unexpected target or adjacent memory instruction");
    if (!statusOnly) {
      const uint32_t desired = enable ? patch->enabled : patch->stock;
      if (word != desired) {
        const uint32_t original = word;
        checkCancelled();
        try {
          writeWord(memory.get(), address, desired);
          const auto after = readInstructions(memory.get(), address);
          word = after.word;
          adjacent = after.adjacent;
          if (word != desired || adjacent != patch->adjacent)
            throw Error("target or adjacent instruction verification failed after write");
          changed = true;
        } catch (const std::exception& error) {
          const std::string writeFailure = error.what();
          try {
            writeWord(memory.get(), address, original);
            const auto restored = readInstructions(memory.get(), address, true);
            word = restored.word;
            adjacent = restored.adjacent;
            rollback = word == original && adjacent == patch->adjacent ?
                "restored_and_verified" : "target_or_adjacent_verification_failed";
          } catch (const std::exception& rollbackError) {
            rollback = std::string("failed: ") + rollbackError.what();
          }
          throw Error(writeFailure);
        }
      }
      if (!stopped->release())
        throw Error("could not detach all threads within deadline; tracer exit will detach tids " +
                    stopped->unreleased());
      detach = "complete";
      stopped.reset();
    } else {
      verifyIdentity(pid, identity);
      library.checkFile();
      if (library.locate(pid) != bias) throw Error("library mappings changed during status read");
    }
  } catch (const std::exception& error) {
    failure = error.what();
  } catch (...) {
    failure = "unexpected native helper failure";
  }
  if (stopped) {
    detach = stopped->release() ? "complete" :
        "pending_kernel_tracer_exit: " + stopped->unreleased();
    stopped.reset();
  }
  std::cout << "{\"ok\":" << (failure.empty() ? "true" : "false")
            << ",\"command\":" << json(command) << ",\"pid\":" << pid;
  if (patch) std::cout << ",\"patch_kind\":" << json(patch->kind);
  if (haveLocation)
    std::cout << ",\"base\":" << json(hex(bias)) << ",\"address\":" << json(hex(address));
  if (haveWord)
    std::cout << ",\"state\":" << json(adjacent == patch->adjacent ? stateFor(word, *patch) : "unknown")
              << ",\"word\":" << json(hex(word, 8))
              << ",\"adjacent_word\":" << json(hex(adjacent, 8));
  if (!hash.empty()) std::cout << ",\"file_sha256\":" << json(hash);
  std::cout << ",\"changed\":" << (changed ? "true" : "false")
            << ",\"rollback\":" << json(rollback) << ",\"detach\":" << json(detach);
  if (!failure.empty()) std::cout << ",\"error\":" << json(failure);
  std::cout << "}\n";
  return failure.empty() ? 0 : 1;
}
