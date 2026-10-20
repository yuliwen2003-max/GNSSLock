// Fixed, reversible experiment for Qualcomm LOC per-SV BDS masking.
// Default --inspect is read-only. --test-split saves B7 state, performs only
// the two fixed B6 actions below, then restores the saved BDS mask and verifies.
// No START/STOP, event registration, SET_PARAMETER, or NV API is available here.
// ABI: Qualcomm qmi_cci.h, verified device qmi_client_* exports.
// TLVs: official location_service_v02.c in location.lnx.0.0:
//   B6 BDS add=12/u64, clear=13/u64; B7 status=01/u32, BDS mask=11/u64.
// Launch with /system/lib64 before /vendor/lib64 in LD_LIBRARY_PATH.
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <time.h>
#include <unistd.h>

namespace {
using Clock = std::chrono::steady_clock;
using Handle = void*;
using Indication = void (*)(Handle, unsigned, void*, unsigned, void*);
using Init = int (*)(void*, unsigned, Indication, void*, void*, uint32_t, Handle*);
using SendRaw = int (*)(Handle, unsigned, void*, unsigned, void*, unsigned,
                       unsigned*, unsigned);
using Release = int (*)(Handle);
constexpr unsigned kSet = 0xb6, kGet = 0xb7;
constexpr uint64_t kLow32 = 0x00000000ffffffffULL;
constexpr uint64_t kHigh31 = 0x7fffffff00000000ULL;
constexpr uint64_t kFull63 = 0x7fffffffffffffffULL;
volatile sig_atomic_t cancelled = 0;

void cancel(int signal) { cancelled = signal; }
void hardTimeout(int) {
  constexpr char text[] = "{\"error\":\"15 second deadline exceeded; host must restore saved baseline\"}\n";
  (void)write(STDERR_FILENO, text, sizeof(text) - 1);
  _exit(124);
}
uint16_t u16(const uint8_t* p) { return p[0] | (static_cast<uint16_t>(p[1]) << 8); }
uint32_t u32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
      (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t u64(const uint8_t* p) {
  return static_cast<uint64_t>(u32(p)) | (static_cast<uint64_t>(u32(p + 4)) << 32);
}
void put64(uint8_t* p, uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(value >> (i * 8));
}
template <typename Callback>
bool walk(const uint8_t* bytes, size_t length, Callback callback) {
  size_t offset = 0;
  while (offset < length) {
    if (length - offset < 3) return false;
    const uint8_t type = bytes[offset];
    const size_t size = u16(bytes + offset + 1);
    offset += 3;
    if (size > length - offset || !callback(type, bytes + offset, size)) return false;
    offset += size;
  }
  return true;
}

struct State {
  pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
  pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
  unsigned expected = 0, length = 0;
  bool received = false, invalid = false;
  std::array<uint8_t, 2048> data{};
};
void indication(Handle, unsigned id, void* data, unsigned length, void* cookie) {
  if ((id != kSet && id != kGet) || !cookie) return;
  auto& state = *static_cast<State*>(cookie);
  pthread_mutex_lock(&state.mutex);
  if (id == state.expected && !state.received) {
    state.invalid = length > state.data.size() || (!data && length != 0);
    if (!state.invalid && length) std::memcpy(state.data.data(), data, length);
    state.length = length;
    state.received = true;
    pthread_cond_signal(&state.condition);
  }
  pthread_mutex_unlock(&state.mutex);
}

template <typename T>
T symbol(void* library, const char* name) {
  void* value = dlsym(library, name);
  if (!value) std::fprintf(stderr, "missing symbol %s\n", name);
  return reinterpret_cast<T>(value);
}

struct Result {
  bool complete = false, maskValid = false;
  uint32_t status = UINT32_MAX;
  uint64_t mask = 0;
};

class Client {
 public:
  Client(Init init, SendRaw send, Release release, void* service)
      : init_(init), send_(send), release_(release), service_(service) {}
  bool open() {
    const int status = init_(service_, 0xffff, indication, &state_, nullptr, 1000, &handle_);
    std::printf("{\"stage\":\"open\",\"client_status\":%d}\n", status);
    return status == 0 && handle_;
  }
  void close() {
    if (!handle_) return;
    const int status = release_(handle_);
    handle_ = nullptr;
    std::printf("{\"stage\":\"close\",\"client_status\":%d}\n", status);
  }
  bool tainted() const { return tainted_; }
  Result exchange(unsigned id, void* payload, unsigned payloadSize,
                  const char* stage, Clock::time_point limit, bool restoring) {
    Result output;
    if ((!restoring && cancelled) || Clock::now() >= limit) return output;
    const auto end = std::min(limit, Clock::now() + std::chrono::seconds(2));
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - Clock::now()).count();
    if (ms <= 0 || !handle_ || tainted_) return output;
    pthread_mutex_lock(&state_.mutex);
    state_.expected = id;
    state_.received = false;
    state_.invalid = false;
    state_.length = 0;
    pthread_mutex_unlock(&state_.mutex);
    std::array<uint8_t, 256> response{};
    unsigned length = 0;
    const int transport = send_(handle_, id, payload, payloadSize, response.data(),
        static_cast<unsigned>(response.size()), &length, static_cast<unsigned>(ms));
    bool resultValid = false;
    uint16_t qmiResult = UINT16_MAX, qmiError = UINT16_MAX;
    const bool responseValid = length <= response.size() && walk(response.data(), length,
        [&](uint8_t type, const uint8_t* bytes, size_t size) {
          if (type != 2) return true;
          if (resultValid || size != 4) return false;
          resultValid = true;
          qmiResult = u16(bytes);
          qmiError = u16(bytes + 2);
          return true;
        });
    std::printf("{\"stage\":\"%s\",\"request\":%u,\"transport\":%d,\"qmi_result\":%u,\"qmi_error\":%u}\n",
        stage, id, transport, qmiResult, qmiError);
    if (transport != 0 || !responseValid || !resultValid || qmiResult != 0 || qmiError != 0) {
      tainted_ = transport != 0 || !responseValid || !resultValid;
      return output;
    }
    pthread_mutex_lock(&state_.mutex);
    while (!state_.received && (restoring || !cancelled)) {
      const auto remaining = std::chrono::duration_cast<std::chrono::nanoseconds>(end - Clock::now()).count();
      if (remaining <= 0) break;
      // Short waits also honor SIGTERM/SIGINT and steady-clock deadline.
      const int64_t waitNs = std::min<int64_t>(remaining, 100000000);
      timespec absolute{};
      clock_gettime(CLOCK_REALTIME, &absolute);
      absolute.tv_nsec += static_cast<long>(waitNs);
      absolute.tv_sec += absolute.tv_nsec / 1000000000;
      absolute.tv_nsec %= 1000000000;
      const int waited = pthread_cond_timedwait(&state_.condition, &state_.mutex, &absolute);
      if (waited != 0 && waited != ETIMEDOUT) break;
    }
    const bool received = state_.received, invalid = state_.invalid;
    if (received && !invalid) {
      bool statusValid = false;
      const bool parsed = walk(state_.data.data(), state_.length,
          [&](uint8_t type, const uint8_t* bytes, size_t size) {
            if (type == 1) {
              if (statusValid || size != 4) return false;
              statusValid = true;
              output.status = u32(bytes);
            } else if (id == kGet && type == 0x11) {
              if (output.maskValid || size != 8) return false;
              output.maskValid = true;
              output.mask = u64(bytes);
            }
            return true;
          });
      output.complete = parsed && statusValid;
    }
    state_.expected = 0;
    pthread_mutex_unlock(&state_.mutex);
    if (!received) tainted_ = true;
    std::printf("{\"stage\":\"%s_ind\",\"complete\":%s,\"modem_status\":%u,\"bds_mask_valid\":%s,\"bds_mask\":\"0x%016llx\"}\n",
        stage, output.complete ? "true" : "false", output.status,
        output.maskValid ? "true" : "false", static_cast<unsigned long long>(output.mask));
    return output;
  }
  Result get(const char* stage, Clock::time_point end, bool restoring = false) {
    return exchange(kGet, nullptr, 0, stage, end, restoring);
  }
  Result set(uint64_t add, uint64_t clear, const char* stage,
             Clock::time_point end, bool restoring = false) {
    std::array<uint8_t, 22> request{};
    request[0] = 0x12;
    request[1] = 8;
    put64(request.data() + 3, add);
    request[11] = 0x13;
    request[12] = 8;
    put64(request.data() + 14, clear);
    std::printf("{\"stage\":\"%s_payload\",\"bds_add\":\"0x%016llx\",\"bds_clear\":\"0x%016llx\"}\n",
        stage, static_cast<unsigned long long>(add), static_cast<unsigned long long>(clear));
    return exchange(kSet, request.data(), static_cast<unsigned>(request.size()), stage, end, restoring);
  }
 private:
  Init init_;
  SendRaw send_;
  Release release_;
  void* service_;
  Handle handle_ = nullptr;
  State state_;
  bool tainted_ = false;
};

bool good(const Result& result) { return result.complete && result.status == 0; }
bool validMask(const Result& result) { return good(result) && result.maskValid; }
bool rootValid(const std::string& root) {
  if (root.empty()) return true;
  if (root.size() < 12 || root.compare(0, 6, "/proc/") != 0 ||
      root.compare(root.size() - 5, 5, "/root") != 0) return false;
  for (size_t i = 6; i < root.size() - 5; ++i)
    if (root[i] < '0' || root[i] > '9') return false;
  return true;
}
}  // namespace

int main(int argc, char** argv) {
  bool test = false, modeSeen = false;
  std::string root;
  for (int i = 1; i < argc; ++i) {
    if ((std::strcmp(argv[i], "--inspect") == 0 ||
         std::strcmp(argv[i], "--test-split") == 0) && !modeSeen) {
      test = std::strcmp(argv[i], "--test-split") == 0;
      modeSeen = true;
    } else if (std::strcmp(argv[i], "--root") == 0 && i + 1 < argc && root.empty()) {
      root = argv[++i];
    } else {
      std::fprintf(stderr, "usage: gpslock-qmi-sv-split [--inspect|--test-split] [--root /proc/PID/root]\n");
      return 2;
    }
  }
  if (!rootValid(root)) return 2;
  setvbuf(stdout, nullptr, _IONBF, 0);
  std::signal(SIGALRM, hardTimeout);
  std::signal(SIGINT, cancel);
  std::signal(SIGTERM, cancel);
  alarm(15);
  const auto start = Clock::now();
  const auto deadline = start + std::chrono::milliseconds(14500);
  const auto testDeadline = start + std::chrono::milliseconds(9000);

  void* cci = dlopen((root + "/vendor/lib64/libqmi_cci.so").c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!cci) { std::fprintf(stderr, "dlopen qmi: %s\n", dlerror()); return 2; }
  void* loc = dlopen((root + "/vendor/lib64/libloc_api_v02.so").c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!loc) { std::fprintf(stderr, "dlopen loc: %s\n", dlerror()); return 2; }
  const auto init = symbol<Init>(cci, "qmi_client_init_instance");
  const auto send = symbol<SendRaw>(cci, "qmi_client_send_raw_msg_sync");
  const auto release = symbol<Release>(cci, "qmi_client_release");
  void* service = symbol<void*>(loc, "loc_qmi_idl_service_object_v02");
  if (!init || !send || !release || !service) return 2;

  Client client(init, send, release, service);
  if (!client.open()) { client.close(); return 3; }
  const Result baseline = client.get("baseline", test ? testDeadline : deadline);
  if (!validMask(baseline)) {
    std::fprintf(stderr, "{\"error\":\"no valid original BDS mask; refusing all writes\"}\n");
    client.close();
    return 4;
  }
  std::printf("{\"baseline_bds_mask\":\"0x%016llx\",\"test_split\":%s}\n",
      static_cast<unsigned long long>(baseline.mask), test ? "true" : "false");
  if (!test || cancelled) { client.close(); alarm(0); return 0; }

  // Restoration is mandatory once a write is attempted, including send timeout.
  const Result first = client.set(kLow32, ~kLow32, "first_low32", testDeadline);
  const Result firstRead = client.tainted() ? Result{} : client.get("first_read", testDeadline);
  bool full63 = false;
  if (good(first) && validMask(firstRead) && firstRead.mask == kLow32 && !cancelled) {
    const Result second = client.set(kHigh31, 0, "second_high31_add_only", testDeadline);
    const Result secondRead = client.tainted() ? Result{} : client.get("second_read", testDeadline);
    full63 = good(second) && validMask(secondRead) && secondRead.mask == kFull63;
  }
  std::printf("{\"aggregate_full63_verified\":%s}\n", full63 ? "true" : "false");

  // A timed-out indication has no transaction ID. Reopen before restoring so
  // stale B6/B7 indications cannot be accepted as restoration acknowledgments.
  Result restored, verified;
  if (client.tainted()) {
    client.close();
    Client recovery(init, send, release, service);
    if (recovery.open()) {
      restored = recovery.set(baseline.mask, ~baseline.mask, "restore", deadline, true);
      if (!recovery.tainted()) verified = recovery.get("restore_read", deadline, true);
    }
    recovery.close();
  } else {
    restored = client.set(baseline.mask, ~baseline.mask, "restore", deadline, true);
    if (!client.tainted()) verified = client.get("restore_read", deadline, true);
    client.close();
  }
  const bool restoreVerified = good(restored) && validMask(verified) && verified.mask == baseline.mask;
  std::printf("{\"restored_original_bds_mask\":%s,\"aggregate_full63_verified\":%s}\n",
      restoreVerified ? "true" : "false", full63 ? "true" : "false");
  alarm(0);
  if (!restoreVerified) return 6;
  return full63 ? 0 : 5;
}
