# SulfurFrame — 임베딩 PoC

게임 창(다른 프로세스)을 IDE 패널 안에 넣는 방식이 실제로 버티는지 검증하는 PoC입니다.
IntelliJ 대신 Swing 창을 호스트로 쓰고, 마인크래프트 대신 같은 스택(LWJGL 3.4.3 + SDL3 + OpenGL)의
테스트 타깃을 씁니다.

| 파일 | 역할 |
|---|---|
| `Win32.java` | FFM(JDK 22+)으로 만든 user32 바인딩. JNA/JNI 없음 |
| `EmbedHost.java` | 호스트 = IntelliJ ToolWindow 대역. 왼쪽 에디터, 오른쪽 AWT `Canvas`(게임 창의 부모) |
| `SdlTarget.java` | 마인크래프트 26.x 대역. SDL3 창 + GL 렌더 루프 + stdin 명령(`stall <ms>`) |
| `SelfTest.java` | 자동 검증. `--robot`이면 실제 마우스/키보드로 포커스·멈춤 시나리오까지 확인 |

## 실행 (JDK 25, 빌드 불필요)

LWJGL jar는 로컬 Gradle 캐시(`~/.gradle/caches/.../org.lwjgl/*/3.4.3`)에서 찾습니다.

```bash
cd poc/embed
java --enable-native-access=ALL-UNNAMED EmbedHost.java                    # 수동
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest         # 자동 (입력 장치 안 건드림)
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot # 자동 + 마우스/키보드 약 30초
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID>     # 이미 떠 있는 창(예: 마인크래프트) 임베드
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot --attach <PID>   # 실제 게임에 자동 검증
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID> --snapshot out.png   # 임베드 상태 스크린샷
./run-vanilla-mc.sh opengl   # 또는 vulkan: Loom 캐시의 바닐라 26.3을 별도 게임 폴더로 실행
```

## 결과 (2026-11-06, Windows 11, AMD Radeon 780M, GL 4.6)

| 항목 | REPARENT (`SetParent`+`WS_CHILD`) | OVERLAY (owned popup이 위치 추적) |
|---|---|---|
| 구조 (부모/소유자, 스타일) | PASS | PASS |
| 뷰포트 리사이즈·이동 추종 | PASS | PASS |
| 게임 멈춤 중 **동기** `SetWindowPos` | IDE 스레드 **약 2.2초 정지** | IDE 스레드 **약 2.2초 정지** |
| 게임 멈춤 중 **비동기** `SetWindowPos` | 0 ms | 0 ms |
| 게임 클릭 → 키가 게임으로 | PASS (*1) | PASS |
| 에디터 클릭 → 키가 에디터로 | PASS (*2) | PASS |
| **게임이 멈췄을 때 에디터 클릭+타이핑** | **PASS, 약 170 ms** (*3) | **FAIL, 3~3.9초 지연** |

REPARENT 결과는 두 번 연속 실행에서 동일했습니다.

### 알아낸 것

1. **창 크기·위치 변경은 항상 `SWP_ASYNCWINDOWPOS`로.** 다른 프로세스의 창에 동기 호출을 하면,
   그 창이 메시지를 처리할 때까지 IDE UI 스레드가 그대로 멈춥니다. 수치로 확인했습니다.
2. **(*1) 자식 창은 클릭만으로 키보드 포커스를 가져가지 않습니다.** 처음에는 게임 쪽 훅으로 해결했고,
   지금은 **호스트만으로 해결합니다** (아래 "모드 없는 click-to-focus" 참고). `SdlTarget`의 훅은 기본으로 꺼져 있습니다
   (`SF_CLICK_TO_FOCUS=1`로 켜서 비교 가능).
3. **(*2) 반대로, Swing 컴포넌트를 클릭해도 네이티브 포커스가 돌아오지 않습니다.** 호스트에서
   뷰포트 바깥 클릭을 감지해 `requestFocus(Cause.MOUSE_EVENT)`로 네이티브 포커스를 요청해야 합니다.
4. **(*3) `SetParent`는 두 프로세스의 입력 큐를 묶습니다.** 게임이 포커스를 가진 채 멈추면, 대책이 없을 땐
   에디터 입력이 약 6초 막혔습니다. 대책: 와치독이 `WM_NULL` 왕복(`SendMessageTimeout`, 200 ms)을 두 번 연속 놓치면
   `AttachThreadInput(false)`로 큐를 떼고, 게임이 회복하면 다시 붙입니다. 한 번만 놓친 걸로 판단하면
   포커스를 넘기는 순간의 짧은 대기를 멈춤으로 오인해서 디바운스가 필요했습니다.
5. **오버레이 방식은 멈춤에 더 약합니다.** 소유된(owned) 창이 멈추면 소유자 창의 활성화가 그 창을 기다리느라
   에디터 입력이 게임이 회복할 때까지 막혔습니다. 입력 큐를 묶지 않으니 더 안전할 거라던 처음 예상과 반대입니다.
   → **REPARENT 방식으로 결정.**

## 실제 마인크래프트 26.3 결과 (2026-11-07, 바닐라, NVIDIA 617.42)

`./run-vanilla-mc.sh opengl|vulkan`으로 Loom 캐시의 바닐라 26.3을 별도 게임 폴더에서 실행한 뒤
`--selftest --robot --attach <PID>`로 확인했습니다. 마인크래프트에는 stall 명령이 없어서, 멈춤은
**게임 창 스레드(렌더 스레드)를 `SuspendThread`로 정지**시켜 재현했습니다.
(`NtSuspendProcess`는 창이 임베드된 상태에서 status 1을 반환하며 실패했습니다.)

| 항목 | GL (REPARENT / OVERLAY) | Vulkan (REPARENT / OVERLAY) |
|---|---|---|
| 구조, 리사이즈·이동 추종 | PASS / PASS | PASS / PASS |
| 멈춤 중 동기 `SetWindowPos` | 약 2.2초 정지 / 약 2.2초 정지 | 약 2.2초 정지 / 약 2.2초 정지 |
| 멈춤 중 비동기 `SetWindowPos` | 0 ms / 0 ms | 0 ms / 0 ms |
| 게임 클릭 → 키가 게임으로 (호스트 처리 전) | FAIL / PASS | FAIL / PASS |
| 게임 클릭 → 키가 게임으로 (**호스트 click-to-focus 후**) | **PASS** / PASS | **PASS** / PASS |
| 에디터 클릭 → 키가 에디터로 | PASS / PASS | PASS / PASS |
| 게임이 멈췄을 때 에디터 타이핑 | **PASS 166~170 ms** / FAIL 3.3초 | **PASS 165~170 ms** / FAIL 3.2초 |

- **GL과 Vulkan 결과가 완전히 같습니다.** HWND 수준 임베딩은 렌더러와 무관하다는 게 확인됐습니다.
- **Vulkan 화면이 자식 창 안에서 정상적으로 그려집니다** (`snapshot-vulkan.png`). 리사이즈 순간에
  `Couldn't acquire next surface texture` 경고가 두 번 났지만, 스왑체인을 다시 만들고 계속 동작했습니다.
- 호스트 click-to-focus를 넣은 뒤로는 REPARENT 항목이 GL·Vulkan 모두 통과합니다. **단, 이 검사는 Win32 포커스만 본 것입니다. SDL이 키보드 포커스를 주지 않아 게임이 실제로는 반응하지 않습니다. 아래 "⚠ 중요" 절을 보세요.**

### 모드 없는 click-to-focus (호스트에서 처리)

1. 게임 창의 `WS_EX_NOPARENTNOTIFY`를 지워서, 게임 창 안의 마우스 다운이 부모(캔버스)에 `WM_PARENTNOTIFY`로 오게 합니다.
2. 캔버스의 `WNDPROC`를 FFM 업콜로 서브클래싱합니다(`installClickToFocus`). 클릭 알림을 받으면 바로 처리하지 않고
   `PostMessage`로 미룹니다. 같은 클릭에 대한 AWT 자체 처리(활성화, 포커스 프록시)가 먼저 끝나야 하기 때문입니다.
3. **`SetFocus(game)`은 AWT 툴킷 스레드에서 부르면 안 됩니다.** 성공을 반환하는데 AWT가 즉시 포커스를 되가져갑니다.
   대신 **AWT와 무관한 도우미 스레드**를 `AttachThreadInput`으로 게임 입력 큐에 잠깐 붙여서 거기서 `SetFocus`합니다.
   게임이 자기 스레드에서 포커스를 가져갈 때 AWT가 가만히 있던 것과 같은 조건입니다.
4. 게임이 멈춘 동안에는 하지 않습니다(`SetFocus`가 멈춘 게임을 기다리게 되므로). 와치독 상태와 100 ms `WM_NULL` 확인으로 거릅니다.

### 실행 환경 메모

- 일부 실행에서 마인크래프트가 리소스 로드 직후 exit 139로 종료됐습니다(hs_err 없음). 호스트를 붙이기 전에도
  일어났으므로 PoC와는 무관해 보입니다. 원인은 조사하지 않았습니다.
- 시작 도중 종료되면 다음 시작에서 마인크래프트가 그래픽 API를 한 번 "default"(GL)로 되돌립니다. 테스트 전에 로그의
  `Using graphics backend ...` 줄을 꼭 확인하세요. 손으로 쓴 `options.txt`(`version:` 줄 없음)도 같은 이유로 GL로 되돌아갑니다.

## 마인크래프트 26.3 스택 (로컬 Loom 캐시의 `mojang_minecraft_info.json`으로 확인)

- Java 25, LWJGL 3.4.3
- **창 라이브러리는 GLFW가 아니라 SDL3**(`lwjgl-sdl`). 명세의 `glfwGetWin32Window`는 26.x에서 쓸 수 없음
- **렌더러는 OpenGL + Vulkan 둘 다 포함**(`lwjgl-opengl`, `lwjgl-vulkan`, `shaderc`, `spvc`, `vma`)

그래서 호스트는 **PID로 최상위 창을 찾습니다**(`Win32.findMainWindowOfProcess`). 이 방식은 GLFW(1.20.5~1.21.x)와
SDL3(26.x), GL과 Vulkan 어느 조합에서도 똑같이 동작하고, 창을 붙이는 것 자체에는 모드가 필요 없습니다. 다만 키보드 입력은 게임 쪽 패치가 필요합니다("⚠ 중요" 절).
모드는 스로틀링(FPS 제한, 일시정지)처럼 게임 안에서만 할 수 있는 일만 맡으면 됩니다.

## 아직 검증하지 않은 것

- 월드 안에서 마우스 잠금(relative mode) 상태에서 ESC로 풀고 에디터로 돌아가기
- DPI가 다른 모니터 사이로 IDE를 옮길 때
- IntelliJ(JBR) 안에서 `SunAwtCanvas` HWND 찾기, 플러그인 환경의 FFM/JNA 선택

## 스로틀러 프로브 1차 결과 (2026-11-07, 바닐라 26.3, GL, 월드 안, G1 기본 설정, -Xmx2G)

`java EmbedHost.java --throttle-probe --attach <PID>` (`ThrottleProbe.java`). 모두 **모드 없이 호스트에서** 한 조작입니다.

| 상태 | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| 일시정지 메뉴 (포커스 잃음) | 18% | 1640 MB | 803 / 948 MB |
| 숨김 (`ShowWindowAsync` 최소화 → 바닐라 10 FPS 제한) | 9% | 1641 MB | 837 / 948 MB |
| + `jcmd VM.set_flag MaxHeapFreeRatio 10` + `GC.run` | - | 1170 MB | 442 / **485 MB** |
| + `EmptyWorkingSet` | 7.6% | **65 MB** | 447 / 485 MB |
| 복원 4초 후 (비율 70/40으로 되돌림) | 18.5% | 613 MB | 471 / 729 MB |

- 복원 후 게임이 응답하기까지 221 ms.
- `GC.run`만 요청하면 힙이 줄지 않습니다(G1 기본 MaxHeapFreeRatio 70%). 실행 중 바꿀 수 있는 플래그라 호스트가 조였다가 되돌리면 됩니다.
- **주의: "PLAYING" 측정은 무효.** 스크린샷을 보니 ESC 이후에도 일시정지 메뉴가 그대로 열려 있었습니다. 실제 플레이 중 기준값은 아직 없습니다.
- 마인크래프트가 리소스 로드 직후 `0xC0000005`로 죽는 일이 잦았습니다(hs_err 없음, OpenAL은 26.3에서 필수라 끌 수 없음). 원인 미확인.

## ⚠ 중요: REPARENT 상태에서 마인크래프트가 키보드 포커스를 받지 못함 (2026-11-07)

앞선 "게임 클릭 → 키가 게임으로 PASS"는 **Win32 포커스(GetGUIThreadInfo)만 확인한 것**이었고, 게임이 실제로 반응하는지는 보지 않았습니다.
SDL 이벤트를 직접 찍어 보니(`SdlTarget` 로그):

| 모드 | 키 이벤트 windowID | SDL 키보드 포커스 | 마우스 이벤트 windowID |
|---|---|---|---|
| REPARENT (자식 창) | **0** | **없음** | 정상 |
| OVERLAY (최상위 창) | 정상 | 있음 | 정상 |

- SDL3는 Windows에서 포커스를 "포그라운드 창 == 자기 창"으로 판단하는 것으로 보입니다. 자식 창은 포그라운드가 될 수 없으니 SDL 키보드 포커스가 끝내 안 잡힙니다.
- 마인크래프트(`SDLEventHandler.getWindowHandle`)는 windowID가 자기 창이 아닌 입력을 버리고, 창이 포커스를 못 받았다고 보므로
  `pauseOnLostFocus`가 매 틱 일시정지 메뉴를 다시 엽니다. **임베드된 바닐라 마인크래프트는 사실상 조작할 수 없습니다.**
- 그래서 스로틀 프로브의 "PLAYING" 값은 여전히 일시정지 메뉴 상태입니다(저장 로그로 확인: 메뉴가 닫혔다가 다시 열린 흔적 없음).

해결 후보:
1. **아주 작은 게임 쪽 패치**: 임베드됐을 때 마인크래프트가 자기 창을 포커스됨으로 보고, windowID 0 입력을 자기 것으로 받게 합니다. 모드(Mixin) 또는
   호스트가 실행 인자로 붙이는 **javaagent**로 가능하고, javaagent면 모드 로더와 상관없이 바닐라에도 붙습니다.
2. **입력할 때만 OVERLAY**: 평소엔 REPARENT, 게임을 클릭하면 최상위 창으로 전환. 멈춤 내성이 떨어집니다(앞의 결과).
3. SDL 쪽 옵션(힌트/환경변수)이 있는지 조사. 아직 찾지 못함.

## 스로틀러 (`Throttler.java`) 2차 결과

포커스·가시성에 따라 ACTIVE / CODING / HIDDEN 상태를 전환합니다. HIDDEN에 들어가면 게임 창을 최소화하고(`ShowWindowAsync`, 바닐라 10 FPS),
5초 뒤에도 숨겨져 있으면 G1 반환 비율을 조이고 GC → `EmptyWorkingSet`을 실행합니다. 다시 보이면 비율(70/40)과 창을 복원합니다. 모드는 필요 없습니다.

| 상태 (바닐라 26.3, GL, 월드 안) | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| 일시정지 메뉴, 게임 포커스 시도 | 64~68% | 700~800 MB | ~450 / 632 MB |
| CODING (일시정지 메뉴) | 65~72% | 700~820 MB | ~500 / 632 MB |
| HIDDEN (최소화 직후) | 6~9% | 변화 없음 | 변화 없음 |
| HIDDEN + 회수 | 17% (회수 직후) | **430~490 MB** | 380 / **420 MB** |
| 복원 4초 후 | 20~65% | 600~670 MB | ~480 / 630 MB |

- 복원 후 응답까지 59~104 ms. GC 플래그는 복원 후 70/40으로 돌아온 것을 `jcmd`로 확인했습니다.
- 일시정지 메뉴만 떠 있어도 CPU를 한 코어의 2/3쯤 씁니다(메뉴 뒤 월드를 계속 그리므로). CODING 상태에서 FPS를 낮추는 건
  바닐라만으로는 안 되고, 위의 게임 쪽 패치가 있으면 같이 할 수 있습니다.
- 마인크래프트가 시작 중 `0xC0000005`로 죽는 현상은 이번에도 3번 중 2번 일어났습니다(원인 미확인).

## 해결: SulfurFrame javaagent (`poc/agent`, 2026-11-07)

`./poc/agent/build.sh`로 `sf-agent.jar`를 만듭니다. JDK 25만 있으면 되고, JDK의 Class-File API를 써서 ASM이나 Gradle은 필요 없습니다.
마인크래프트 메서드 세 개에 대해 "리턴 직전에 훅 호출 하나 끼우기"만 합니다.

| 패치 위치 | 훅 | 하는 일 |
|---|---|---|
| `Window.isFocused()` (`Minecraft.isWindowActive()`도 사용) | `SfHooks.focused` | Win32 `GetFocus()`가 이 프로세스의 창이면 포커스로 봄. SetParent로 입력 큐가 묶여 있어서 에디터에 포커스가 있으면 "남의 창"으로 보임 |
| `SDLEventHandler.getWindowHandle(event)` | `SfHooks.windowHandle` | SDL이 windowID 0으로 보낸 키 이벤트를 게임 창의 것으로 받음 |
| `FramerateLimitTracker.getFramerateLimit()` | `SfHooks.fpsLimit` | 포커스가 없으면 FPS 상한 적용 (`codingFps=15`) |

- **붙이는 방법:** 호스트가 실행 중인 게임에 Attach API로 붙이고(`EmbedHost --agent poc/agent/sf-agent.jar ...`) 이미 로드된 클래스를 재변환합니다.
  **게임 재시작이 필요 없습니다.** 시작할 때 붙이려면 `SF_AGENT=poc/agent/sf-agent.jar ./run-vanilla-mc.sh opengl`. 다만 시작 시 붙이는 경로는 아직 실행해 보지 않았습니다.
- 두 번 붙여도 설정만 갱신하고 패치를 중복으로 넣지 않습니다.
- 모드 로더(Fabric 등)와 상관없이 바닐라 클래스 이름 그대로 패치합니다. Fabric 환경에서는 검증하지 않았습니다.

### 결과 (바닐라 26.3, GL, 월드 안, 실행 중에 에이전트 attach)

- **실제 플레이 가능:** "Back to Game" 클릭 → 플레이 화면 복귀 확인(스크린샷). **E 키 → 인벤토리 열림**(키보드 입력 확인).
  에디터를 클릭하면 일시정지 메뉴가 뜨고 저장 로그가 남습니다.
- 에이전트를 붙인 상태에서 임베딩 셀프테스트의 REPARENT 항목은 전부 통과합니다(멈췄을 때 에디터 입력 169 ms).

| 상태 | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| **PLAYING (실제 플레이)** | **92~111%** | 880~1380 MB | ~530 / 632~679 MB |
| **CODING (일시정지 + 15 FPS 상한)** | **9~10%** | 변화 없음 | 변화 없음 |
| HIDDEN (최소화) | 5~12% | 변화 없음 | 변화 없음 |
| HIDDEN + 회수 | - | **500 MB** | 384 / **420 MB** |
| 복원 직후 | 11~22% | 530~570 MB | ~380 / 422~632 MB |

에이전트가 없을 때 CODING은 65~72%였습니다. FPS 상한 덕분에 코딩 중 게임 CPU가 약 1/7로 줄었습니다.
