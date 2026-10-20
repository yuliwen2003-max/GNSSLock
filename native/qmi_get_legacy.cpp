// Read-only Qualcomm LOC diagnostic. The only service request sent is GET_PARAMETER 0xd8.
// Fixed parameter 2 reads legacy constellation configuration; it does not change primary GNSS.
// No GNSS session, event registration, configuration, or NV write is performed.
// ABI verified against the device's libqmi_cci.so and Qualcomm public headers:
// https://github.com/qualcomm/qmi-framework/blob/main/include/qmi_cci.h
// (the device exports the older, ABI-equivalent qmi_client_* names).
// TLVs verified in Qualcomm location_service_v02.c, location.lnx.0.0 branch:
// qmiLocGetParameterReqMsgT_data_v02 and qmiLocGetParameterIndMsgT_data_v02.
// Request TLV 0x01 is uint32 parameter 2. Indication TLVs: status 0x01/u32,
// paramType 0x02/u32, optional minSvElevation 0x10/u8, constellationConfig 0x11/u64.
#include <array>
#include <cerrno>
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
constexpr unsigned kGetParameter = 0xd8;
constexpr uint32_t kConstellationDisableConfig = 2;
using Handle = void*;
using Indication = void (*)(Handle, unsigned, void*, unsigned, void*);
using Init = int (*)(void*, unsigned, Indication, void*, void*, uint32_t, Handle*);
using SendRaw = int (*)(Handle, unsigned, void*, unsigned, void*, unsigned,
                       unsigned*, unsigned);
using Release = int (*)(Handle);

struct State {
  pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
  pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
  bool received = false;
  bool oversized = false;
  unsigned length = 0;
  std::array<uint8_t, 2048> data{};
};

void hardTimeout(int) {
  constexpr char message[] = "{\"error\":\"diagnostic exceeded 10 seconds; exiting\"}\n";
  (void)write(STDERR_FILENO, message, sizeof(message) - 1);
  _exit(124);
}

void indication(Handle, unsigned id, void* data, unsigned length, void* cookie) {
  // Do not inspect or print unrelated indications, which can contain location.
  if (id != kGetParameter || !cookie) return;
  auto* state = static_cast<State*>(cookie);
  pthread_mutex_lock(&state->mutex);
  if (!state->received) {
    state->oversized = length > state->data.size() || (!data && length != 0);
    if (!state->oversized && length) std::memcpy(state->data.data(), data, length);
    state->length = length;
    state->received = true;
    pthread_cond_signal(&state->condition);
  }
  pthread_mutex_unlock(&state->mutex);
}

uint16_t u16(const uint8_t* p) {
  return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}
uint32_t u32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) |
      (static_cast<uint32_t>(p[1]) << 8) |
      (static_cast<uint32_t>(p[2]) << 16) |
      (static_cast<uint32_t>(p[3]) << 24);
}

template <typename Callback>
bool walkTlvs(const uint8_t* data, size_t length, Callback callback) {
  size_t offset = 0;
  while (offset < length) {
    if (length - offset < 3) return false;
    const uint8_t type = data[offset];
    const size_t size = u16(data + offset + 1);
    offset += 3;
    if (size > length - offset) return false;
    if (!callback(type, data + offset, size)) return false;
    offset += size;
  }
  return true;
}

bool responseAccepted(const uint8_t* data, size_t length) {
  bool present = false;
  uint16_t result = 0xffff, error = 0xffff;
  const bool parsed = walkTlvs(data, length,
      [&](uint8_t type, const uint8_t* value, size_t size) {
        if (type != 2) return true;
        if (present || size != 4) return false;
        present = true;
        result = u16(value);
        error = u16(value + 2);
        return true;
      });
  if (!parsed || !present) {
    std::fprintf(stderr, "{\"error\":\"malformed or missing QMI response result\"}\n");
    return false;
  }
  std::printf("{\"stage\":\"response\",\"qmi_result\":%u,\"qmi_error\":%u}\n",
              result, error);
  return result == 0 && error == 0;
}

uint64_t u64(const uint8_t* p) {
  return static_cast<uint64_t>(u32(p)) | (static_cast<uint64_t>(u32(p + 4)) << 32);
}

int printParameter(const State& state) {
  if (state.oversized) {
    std::fprintf(stderr, "{\"error\":\"invalid indication length\"}\n");
    return 4;
  }
  bool statusValid = false, paramTypeValid = false, configValid = false;
  bool elevationValid = false;
  uint32_t status = 0, paramType = 0;
  uint64_t config = 0;
  const bool parsed = walkTlvs(state.data.data(), state.length,
      [&](uint8_t type, const uint8_t* bytes, size_t size) {
        if (type == 1) {
          if (statusValid || size != 4) return false;
          statusValid = true;
          status = u32(bytes);
        } else if (type == 2) {
          if (paramTypeValid || size != 4) return false;
          paramTypeValid = true;
          paramType = u32(bytes);
        } else if (type == 0x10) {
          if (elevationValid || size != 1) return false;
          elevationValid = true;
          // Not requested; validate its shape without retaining the value.
        } else if (type == 0x11) {
          if (configValid || size != 8) return false;
          configValid = true;
          config = u64(bytes);
        }
        return true;
      });
  if (!parsed || !statusValid || !paramTypeValid) {
    std::fprintf(stderr, "{\"error\":\"malformed GET_PARAMETER indication\"}\n");
    return 4;
  }
  std::printf("{\"stage\":\"legacy_parameter\",\"modem_status\":%u,\"paramType\":%u,"
              "\"constellationConfig_valid\":%s,\"constellationConfig\":",
              status, paramType, configValid ? "true" : "false");
  if (configValid) std::printf("%llu", static_cast<unsigned long long>(config));
  else std::printf("null");
  std::printf("}\n");
  if (paramType != kConstellationDisableConfig) return 4;
  return status == 0 ? 0 : 5;
}

template <typename T>
T symbol(void* library, const char* name) {
  dlerror();
  void* result = dlsym(library, name);
  if (!result) {
    const char* error = dlerror();
    std::fprintf(stderr, "missing symbol %s: %s\n", name, error ? error : "unknown");
  }
  return reinterpret_cast<T>(result);
}

bool rootPrefixValid(const std::string& root) {
  if (root.empty()) return true;
  constexpr char prefix[] = "/proc/";
  constexpr char suffix[] = "/root";
  if (root.compare(0, sizeof(prefix) - 1, prefix) != 0 ||
      root.size() < 12 || root.compare(root.size() - 5, 5, suffix) != 0) return false;
  for (size_t i = 6; i < root.size() - 5; ++i) {
    if (root[i] < '0' || root[i] > '9') return false;
  }
  return true;
}
}  // namespace

int main(int argc, char** argv) {
  std::string root;
  if (argc == 3 && std::strcmp(argv[1], "--root") == 0) root = argv[2];
  else if (argc != 1) {
    std::fprintf(stderr, "usage: gpslock-qmi-get-legacy [--root /proc/PID/root]\n");
    return 2;
  }
  if (!rootPrefixValid(root)) {
    std::fprintf(stderr, "invalid process root prefix\n");
    return 2;
  }
  setvbuf(stdout, nullptr, _IONBF, 0);
  std::signal(SIGALRM, hardTimeout);
  alarm(10);

  void* cci = dlopen((root + "/vendor/lib64/libqmi_cci.so").c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!cci) {
    std::fprintf(stderr, "dlopen libqmi_cci: %s\n", dlerror());
    return 2;
  }
  void* loc = dlopen((root + "/vendor/lib64/libloc_api_v02.so").c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!loc) {
    std::fprintf(stderr, "dlopen libloc_api_v02: %s\n", dlerror());
    return 2;
  }
  const auto init = symbol<Init>(cci, "qmi_client_init_instance");
  const auto send = symbol<SendRaw>(cci, "qmi_client_send_raw_msg_sync");
  const auto release = symbol<Release>(cci, "qmi_client_release");
  void* service = symbol<void*>(loc, "loc_qmi_idl_service_object_v02");
  if (!init || !send || !release || !service) return 2;

  State state;
  Handle client = nullptr;
  const int openStatus = init(service, 0xffff, indication, &state, nullptr, 3000, &client);
  std::printf("{\"stage\":\"open\",\"client_status\":%d}\n", openStatus);
  if (openStatus != 0 || !client) {
    if (client) (void)release(client);
    alarm(0);
    return 3;
  }

  // The public service table specifies exactly TLV 0x01, length 4, parameter 2.
  // Raw TLVs avoid assumptions about vendor in-memory struct layout.
  std::array<uint8_t, 7> request{{0x01, 0x04, 0x00, 0x02, 0x00, 0x00, 0x00}};
  std::array<uint8_t, 256> response{};
  unsigned received = 0;
  const int sendStatus = send(client, kGetParameter, request.data(), static_cast<unsigned>(request.size()),
      response.data(), static_cast<unsigned>(response.size()), &received, 2000);
  std::printf("{\"stage\":\"send\",\"transport_status\":%d}\n", sendStatus);
  int result = 3;
  if (sendStatus == 0 && received <= response.size() &&
      responseAccepted(response.data(), received)) {
    timespec deadline{};
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 4;
    pthread_mutex_lock(&state.mutex);
    while (!state.received) {
      const int waitStatus = pthread_cond_timedwait(&state.condition, &state.mutex, &deadline);
      if (waitStatus == ETIMEDOUT) break;
      if (waitStatus != 0) break;
    }
    const bool gotIndication = state.received;
    pthread_mutex_unlock(&state.mutex);
    if (gotIndication) result = printParameter(state);
    else {
      std::fprintf(stderr, "{\"error\":\"GET_PARAMETER indication timed out\"}\n");
      result = 4;
    }
  }

  // Synchronous release drains callbacks before State leaves scope.
  const int closeStatus = release(client);
  std::printf("{\"stage\":\"close\",\"client_status\":%d}\n", closeStatus);
  alarm(0);
  if (closeStatus != 0 && result == 0) result = 6;
  // Keep vendor DSOs mapped until process exit; avoid unloading callback code.
  return result;
}
