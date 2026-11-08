# SulfurFrame IntelliJ 플러그인 (PoC)

마인크래프트 모드 프로젝트를 열면, 빌드 파일에서 **마인크래프트 버전, 모드 로더, 로더 버전, 필요한 Java**를 읽어 툴윈도우에 보여 줍니다.
그리고 게임 창을 툴윈도우 안에 넣습니다.

![in-ide](docs/in-ide.png)

## 기능
- **버전 자동 결정** (`profile/ProjectProfile.java`): 아무것도 실행하지 않고(Gradle 동기화 없이) 파일만 읽습니다.
  - 빌드 스크립트의 플러그인 id로 로더를 판별: `fabric-loom` / `net.fabricmc.fabric-loom` / `dev.architectury.loom`,
    `net.neoforged.moddev` / `net.neoforged.gradle`, `net.minecraftforge.gradle`, ForgeGradle 2의 `net.minecraftforge.gradle.forge`, `org.quiltmc.loom`
  - 버전을 읽는 곳(우선순위 순):
    1. `gradle.properties` (프로젝트에서 settings 루트까지)
    2. 빌드 스크립트의 좌표: `com.mojang:minecraft:X`, `fabric-loader`, `net.minecraftforge:forge:MC-FORGE`, `neoForge { version }`, FG2의 `minecraft { version = "1.12.2-..." }`
    3. `gradle/libs.versions.toml`
  - 주석과 `${...}` 참조를 처리합니다. NeoForge 버전만 있으면 MC 버전을 거꾸로 계산합니다(21.1.x → 1.21.1).
    필요한 Java는 Mojang `javaVersion` 기준입니다(≤1.16: 8, 1.17: 16, ~1.20.4: 17, ~1.21.x: 21, 26.x: 25).
  - 근거(evidence)를 로그로 보여 줍니다.
- **Run client**: 프로젝트의 `gradlew runClient`를 실행하고(Loom, ModDevGradle, ForgeGradle 모두 같은 이름) 새로 뜬 마인크래프트 창을 찾아 자동으로 임베드합니다.
- **임베딩** (`embed/GameEmbedder.java`): `poc/embed`에서 검증한 규칙을 모두 옮겼습니다.
  - 비동기 창 조작
  - 모드 없는 click-to-focus, `WM_MOUSEACTIVATE` 차단, 에디터 클릭 시 포커스 회수
  - 멈춤 와치독, 임베드 직후 크기 nudge
- **javaagent 내장**: 플러그인 안의 `sf-agent.jar`를 ASCII 경로에 풀어서 Attach API로 붙입니다. 코딩 중 FPS 제한과 SDL3 포커스 보정을 맡습니다.
- **스로틀러**: 툴윈도우가 숨겨지면 게임을 최소화하고, 5초 뒤 힙을 줄이고 워킹셋을 트림합니다. 다시 보이면 원래대로 돌립니다.

## 실제 IDE에서만 생기는 문제와 해결
- **캔버스 HWND 찾기**: 화면 좌표로 창을 찾는 방식(WindowFromPoint)은 IntelliJ에서 실패했습니다.
  IntelliJ는 `--add-opens java.desktop/sun.awt.windows=ALL-UNNAMED`로 실행되므로 `AWTAccessor`로 peer를 꺼내 `WComponentPeer.getHWnd()`로 읽습니다.
- **툴윈도우를 숨기거나 옮기거나 플로팅하면 IntelliJ가 컴포넌트를 떼어 내고**, 캔버스의 네이티브 창이 파괴됩니다. 자식인 게임 창도 함께 파괴됩니다.
  → `removeNotify` 직전에 게임을 **데스크톱 기준 최상위 창으로 되돌리고 최소화한 뒤 감추고**(park), `addNotify` 뒤 새 캔버스에 다시 붙입니다.
  - park 위치를 IDE 프레임으로 했을 때, 플로팅 창을 다시 도킹하면 그 프레임이 사라지면서 게임 창이 함께 없어졌습니다.
  - 그냥 감추기만(`SW_HIDE`) 하면 마인크래프트가 최소화로 보지 않아서 FPS가 그대로였습니다. 그래서 최소화한 뒤 감추고, 이 상태를 숨김으로 처리합니다.

## 빌드와 테스트 (Gradle 없이, 설치된 IDE 사용)
```bash
./sf-idea-plugin/build.sh          # → sf-idea-plugin/build/SulfurFrame (플러그인 폴더)
```
- IntelliJ IDEA 2026.2+(JBR 25, FFM 사용 가능)의 `lib/*.jar`에 대고 컴파일합니다. 에이전트는 `poc/agent/sf-agent.jar`를 넣습니다.
- **샌드박스 자동 테스트**: 별도의 config/system/plugins 폴더로 IDE를 띄워서 사용자 설정을 건드리지 않습니다.
  - Gradle과 Maven 플러그인을 끄고, 동의 창은 `-Djb.consents.confirmation.enabled=false` 등으로 건너뜁니다.
  - `-Dsulfurframe.autotest.pid=<실행 중인 마인크래프트 PID>`를 주면 툴윈도우가 자동으로 열립니다. 그다음 붙이기, 플로팅, 재도킹, 숨김, 보이기를 차례로 검사해서 `%TEMP%/sulfurframe-autotest.txt`와 스크린샷으로 남깁니다.

결과 (IntelliJ IDEA 2026.2.3, 바닐라 1.21.1):
```
PASS profile detected  [FABRIC 1.21.1 0.17.2 (Java 21)]
PASS embedded in the tool window
PASS game fills the canvas  [game 450x725, canvas 450x725]
PASS survives floating the tool window
PASS survives re-docking
PASS hidden -> game minimized  [iconic true, state HIDDEN]
PASS shown -> game restored  [iconic false, state CODING]
failures: 0
```
**Run client 흐름** (`-Dsulfurframe.autotest.runclient=true`, lucid-study-mod 안쪽 프로젝트의 임시 사본, Fabric 1.21.1):
```
PASS profile detected  [FABRIC 1.21.1 0.17.2 (Java 21)]
PASS Run client -> game embedded  [after 25 s (첫 실행 97 s)]
PASS game fills the canvas  [game 450x725, canvas 450x725]
```
- 버튼을 누르면 프로젝트의 `gradlew.bat runClient`가 실행되고, 개발 클라이언트 창이 뜨면 에이전트를 붙인 뒤 툴윈도우에 넣습니다.
- 리소스를 로드하는 동안은 게임이 바빠서 와치독이 잠깐씩 입력 큐를 뗐다 붙입니다. 정상 동작입니다.
- **실제 프로젝트에서 찾은 문제:** 이 프로젝트의 wrapper는 Gradle 8.12인데, 쓰는 Loom 1.11-SNAPSHOT은 8.14 이상이 필요해서
  `runClient`가 실패합니다(원본의 `gradle-wrapper.properties`는 git에서 수정된 상태). 테스트는 사본의 wrapper만 캐시에 있는 9.4.1로 바꿔서 진행했고,
  원본은 건드리지 않았습니다. 플러그인은 이제 Gradle의 "What went wrong" 내용을 툴윈도우 로그에 그대로 보여 줍니다.

버전 감지기 fixture 테스트:
```bash
javac -d out sf-idea-plugin/src/main/java/dev/sulfurframe/idea/profile/ProjectProfile.java sf-idea-plugin/src/test/java/dev/sulfurframe/idea/profile/ProfileSelfTest.java
java -cp out dev.sulfurframe.idea.profile.ProfileSelfTest sf-idea-plugin/src/test/resources/profiles [실제 프로젝트 폴더...]
```
fixture 6개(NeoForge MDK, Forge 1.20.1 MDK, Forge FG2 1.12.2, Quilt, kts 직접 선언, 버전 카탈로그)와 로컬 실제 프로젝트 4개를 정확히 감지했습니다.

## 아직 안 된 것
- Linux: F11 전체화면, 실제 Linux 데스크톱(GNOME/KDE)과 GPU 드라이버 확인. 26.x(SDL3)와 1.12.2(LWJGL 2)는 PoC에서 확인(`poc/embed/README.md`), 플러그인으로는 1.21.1만 시험. 아래 "Linux (X11)" 참고.
- 모드마다 빌드와 월드가 따로인 불편(공유 인스턴스/월드), Gradle 플러그인 기반 정식 빌드, 마켓플레이스 배포 형태는 아직입니다.

## 컨테이너 폴더와 멀티 버전 프로젝트 (`profile/Workspace.java`)

열린 폴더에서 **실행할 수 있는 클라이언트를 모두 찾아** 툴윈도우 위쪽 콤보박스에 보여 줍니다(최신 MC 버전 순).
선택은 프로젝트마다 기억합니다(`PropertiesComponent`).
"Run client"는 고른 대상이 속한 빌드 루트의 wrapper로 해당 작업 경로를 실행합니다.

![container](docs/container-target.png)

| 구조 | 판별 방법 | 실행 작업 |
|---|---|---|
| 단일 프로젝트 | 폴더 자체가 모드 빌드 | `runClient` |
| **컨테이너** (모드 여러 개를 담은 레포, 예: lucid-study-mod) | 하위 폴더마다 독립된 `settings.gradle` | 각 빌드에서 `runClient` |
| **멀티 프로젝트** (buildpali, 버전별 모듈) | `settings`의 `include(...)` 중 모드인 서브프로젝트 | `:core:mod-fabric-1_21_1:runClient` |
| **Architectury** | 서브프로젝트의 `architectury { fabric() / neoForge() / forge() }`. `common`은 제외 | `:fabric:runClient`, `:neoforge:runClient` |
| **Stonecutter** | `settings`의 `stonecutter { versions("1.20.1", "1.21.1") }`, 버전별 `versions/<v>/gradle.properties` | `:1.21.1:runClient` |

- **kts 변수 추적:** `val mcVersion = rootProject.property("mc_1_21_1_version")`, `property("deps.x")`, groovy `def x = rootProject.y`를 따라가서 버전을 읽습니다.
- **플러그인 판별 예외:** `apply false`로 선언만 한 플러그인과 `subprojects { }` 안에서 적용하는 플러그인은 그 프로젝트 자신의 로더로 보지 않습니다.
- **폴더 이름은 버전 힌트로만:** `mod-forge-1_21_1` 같은 이름은 버전을 짐작하는 데만 씁니다. 빌드 스크립트에 로더 근거가 없는 스텁 모듈은 대상에서 뺍니다.
- **중첩 settings 처리:** 다른 빌드에 include된 폴더는 자기 `settings.gradle`이 있어도 독립 빌드로 보지 않고, 프로퍼티도 상위 빌드 루트에서 읽습니다.
- **제외 폴더:** `.git`, `.gradle`, `.claude/worktrees` 같은 점(.) 폴더와 `build`, `run`, `src`는 훑지 않습니다.

검증:
```
PASS container     -> [runClient FABRIC 1.21.1 0.17.2, runClient NEOFORGE 1.21.1 21.1.265]
PASS multiproject  -> [:core:mod-fabric-1_21_1:runClient FABRIC 1.21.1 0.16.10, :core:mod-fabric-1_20_1:runClient FABRIC 1.20.1 0.15.11]
PASS architectury  -> [:fabric:runClient FABRIC 1.21.1 0.16.10, :neoforge:runClient NEOFORGE 1.21.1 21.1.265]
PASS stonecutter   -> [:1.21.1:runClient FABRIC 1.21.1 0.16.10, :1.20.1:runClient FABRIC 1.20.1 0.16.10]
```
실제 폴더 결과는 다음과 같습니다.
- **lucid-study-mod:** 대상 5개(savedeath·untitled1 1.21.7, 루트·lucid-study-mod·lucid-study-mod2 1.21.1). 루트는 "(no gradlew)"로 표시됩니다.
- **buildpali:** Fabric 모듈 5개(`:core|gui|terrain|3dio|ai:mod-fabric-1_21_1`)와 Forge `:core:mod-forge-1_21_1`(52.1.6). worktree 중복과 Forge 스텁은 빠졌습니다.

샌드박스 IDE에서 lucid-study-mod 컨테이너 전체 사본을 열고 `lucid-study-mod`를 골라 Run client를 눌렀습니다.
대상 5개가 나열됐고, 안쪽 프로젝트의 wrapper로 실행돼 82초 뒤 툴윈도우에 임베드됐습니다(테스트 4/4 PASS).
```bash
java -cp out dev.sulfurframe.idea.profile.WorkspaceSelfTest sf-idea-plugin/src/test/resources/workspaces [실제 폴더...]
```

## 화면 배치와 F11 전체화면

- **16:9 비율 유지**(기본값): 게임을 패널 안에서 16:9로 맞추고, 남는 곳은 검은 띠로 채웁니다. 세로로 긴 툴윈도우에서도 화면이 늘어나지 않습니다.
  "16:9" 체크를 끄면 패널을 꽉 채웁니다.
- **"Open as tab"**: 게임을 가로로 넓은 에디터 영역의 "Minecraft" 탭으로 옮깁니다(`GameTab`, `FileEditorProvider`).
  탭을 닫으면 툴윈도우로 돌아옵니다. 옮길 때 캔버스가 새로 만들어지는데, 게임은 park 후 다시 붙습니다.
  ![tab](docs/editor-tab.png)
- **F11 (게임 안의 전체화면 키)**: 임베드 상태에서 F11을 누르면 GLFW와 SDL은 창에 `WS_POPUP`만 추가합니다. 자식 창이라 패널 안에 그대로 남습니다.
  다시 누르면 `WS_POPUP`이 빠지고 **제목 표시줄이 생겨서** 패널 안에 창 테두리가 붙습니다(1.21.1에서 실측).
  → 와치독이 스타일 변화를 감지합니다.
  - `WS_POPUP`이 생기면 게임을 IDE에서 빼서 IDE가 있는 모니터를 꽉 채웁니다.
  - `WS_POPUP`이 빠지면 제목 표시줄을 걷어내고 패널에 다시 넣습니다.

샌드박스 자동 테스트(12개 항목), **1.21.1(GLFW)과 26.3(SDL3) 모두 12/12 통과**:
```
PASS 16:9 letterbox                     [game 450x253 (1.779) in canvas 450x708]
PASS F11 -> fullscreen                  [fullscreen true, child false, rect 1920x1200, monitor 1920x1200]
PASS F11 again -> embedded, no title bar
PASS open as editor tab
PASS tab closed -> back in the tool window
```
F11 검사는 실제 키를 보냅니다. 그래서 키보드 포커스가 게임에 있을 때만 보내고, 그렇지 않으면 실패로 기록합니다.

## IDE가 죽었을 때 게임 보호

**실측 결과:** 임베드한 상태에서 IDE(호스트) 프로세스를 강제 종료하면, **약 2.75초 뒤 게임 창이 파괴됩니다.** 자식 창은 부모와 함께 파괴되기 때문입니다.
게임 프로세스는 창 없이 남아서 더 쓸 수 없고, 마지막 자동 저장 이후의 월드 변경도 잃습니다.
- **외부에서 구할 수 없음:** 다른 프로세스가 10ms 간격으로 감시하다가 첫 신호에 게임 창을 데스크톱으로 빼려 했지만 실패했습니다.
  부모 창이 사라지는 순간 게임 창도 이미 파괴되는 중이었고, IDE의 GUI 스레드·프로세스 종료 신호는 그보다 늦게 왔습니다.
  → **진짜 자식 창 구조에서는 강제 종료 시 창을 지킬 수 없습니다.** 대신 작업을 잃지 않게 합니다.

| IDE 종료 방식 | 처리 | 결과(실측) |
|---|---|---|
| 정상 종료, 재시작, 플러그인 해제 | 툴윈도우 dispose → detach | 게임이 일반 창으로 남음 |
| `taskkill`(/F 없음), 로그오프 | JVM 셧다운 훅 `releaseForShutdown` | **게임이 일반 창으로 남음** (Confirm Exit → Exit, 2초 후 IDE 종료, 게임 창 유지) |
| **강제 종료(/F, 작업 관리자), 크래시** | **에이전트의 구명보트**(`Lifeboat`) | **게임이 월드를 저장하고 1~2초 안에 종료** |

**구명보트**(`poc/agent/.../Lifeboat.java`): 게임 안의 데몬 스레드가 250ms마다 자기 창을 확인합니다.
- **동작:** 다른 창의 자식인(임베드된) 상태에서 창이 사라지면, 게임에 평소의 종료를 요청합니다.
  - GLFW: `glfwSetWindowShouldClose`
  - SDL: `SDL_EVENT_QUIT` 넣기
  - LWJGL 2: `close_requested`
  
  마인크래프트는 "Stopping!" → 플레이어와 월드 저장 → 서버 종료 순서로 정상 종료합니다.
- **건드리지 않는 경우:** 먼저 detach된(최상위) 창이 닫히는 것은 평범한 종료라 그대로 둡니다.
- **창 핸들 얻는 법:** `GLFWNativeWin32`, SDL 창 프로퍼티(`SDL.window.win32.hwnd`), LWJGL 2 `WindowsDisplay.hwnd`
- **Win32 호출:** LWJGL 3 JNI를 쓰고, LWJGL 2 게임은 JNA를 씁니다(1.8 이상에 포함).

검증(호스트 강제 종료 → 게임 로그):
- 1.21.1 (GLFW), 26.3 (SDL3), 1.12.2 (LWJGL 2 + JNA): 모두 "Stopping!"과 월드 저장 후 1초 안에 프로세스 종료
- **실제 플러그인 흐름**: 샌드박스 IDE가 붙인 에이전트로 1.21.1 임베드 → IDE `/F` 강제 종료 → 2초 후 저장하고 종료
- **월드를 연 상태**(lucid-study-mod `runClient --quickPlaySingleplayer`, 1.21.1 Fabric): 호스트 `/F` → "Saving worlds" → "All chunks are saved"(3개 차원) → 7초 안에 종료, `level.dat` 갱신 확인
- **Run client로 띄운 게임 + IDE 정상 종료**(Confirm Exit → Exit): 게임은 일반 창으로 계속 돌아갑니다.
  게임 부모는 Gradle 데몬이고 IDE의 자식은 Gradle 클라이언트뿐이라, IDE가 꺼져도 빌드가 취소되지 않습니다. 클라이언트는 게임이 끝날 때 함께 종료됩니다.

한계: 1.7.10은 LWJGL 3 JNI도 JNA도 없어서 구명보트를 쓸 수 없습니다(로그에 "unavailable"로 남김).

## 핫스왑 (디버거 없이)

**흐름:** 저장하거나 "Hotswap" 버튼을 누르면 다음 순서로 진행됩니다.
1. 마지막 교체 이후 바뀐 `.java` 파일만 javac로 바로 컴파일해서 소스셋의 `build/classes/...`에 씁니다(`FastCompile.java`).
   javac에 필요한 정보(소스 폴더, 출력 폴더, 컴파일 클래스패스, release, 인코딩, toolchain javac)는 Run client 때 Gradle init script(`sfClasspath` 태스크)로 **한 번만** 받아서
   `%TEMP%\sulfurframe\classpath-*.txt`에 캐시합니다. 빌드 파일(`build.gradle*`, `settings.gradle*`, `gradle.properties`, `libs.versions.toml`)이 바뀌면 다시 받습니다.
   캐시가 아직 없거나, Kotlin 파일이 바뀌었거나, PID로 붙여서 어떤 소스가 바뀌었는지 모르면 예전처럼 Gradle `<경로>:classes`(+ `clientClasses`)로 컴파일합니다.
2. `build/classes` 아래에서 수정 시각이 바뀐 `.class` 파일을 찾습니다.
3. 목록 파일을 ASCII 경로에 쓰고, Attach API로 에이전트에 `redefine=<목록>`을 보냅니다.
4. 에이전트(`poc/agent/.../Hotswap.java`)가 이미 로드된 클래스를 `Instrumentation.redefineClasses`로 교체하고 결과 파일을 씁니다.

- **javac 경로의 범위:** 고친 파일만 컴파일하고(나머지는 출력 폴더의 클래스를 참조), 어노테이션 프로세서는 끕니다(`-proc:none`, Mixin refmap은 건드리지 않음). 핫스왑이 반영할 수 있는 것은 어차피 메서드 본문뿐이라 이걸로 충분합니다. 컴파일 오류가 나면 그 파일들은 다음 교체 때 다시 시도합니다.

- **디버거를 쓰지 않는 이유:** IntelliJ의 HotSwap(JDWP)은 게임을 디버거로 띄워야 하는데, `runClient`에 디버그 포트를 끼우면 Gradle 데몬과 충돌하기 쉽습니다. 이미 붙어 있는 에이전트를 쓰면 이 문제가 없습니다.
- **반영되는 범위:** Run client는 게임을 **JetBrains Runtime(JBR)**과 `-XX:+AllowEnhancedClassRedefinition`으로 띄웁니다(`Jbr.java`, Gradle init script로 runClient의 실행 파일과 JVM 인자만 바꾸고 프로젝트 빌드 파일은 건드리지 않음). JBR에 들어 있는 DCEVM 덕분에 **메서드와 필드를 추가하거나 지워도** 그대로 교체됩니다.
  - JBR은 게임이 필요한 Java 버전과 같아야 합니다. `~/.jdks`(IntelliJ의 SDK 다운로드)에서 찾고, 버전이 맞으면 IDE 자신의 JBR(2026.x는 25, 마인크래프트 26.x용)도 씁니다. 1.21.x는 `jbr-21`이 필요합니다.
  - 맞는 JBR이 없으면 일반 JVM으로 띄우고, 그때는 **메서드 본문 변경**만 반영됩니다. 메서드나 필드를 추가·삭제하면 해당 클래스만 "restart needed"로 알려 주고, 나머지 클래스는 그대로 반영합니다(한 번에 실패하면 클래스별로 다시 시도).
  - 새로 추가한 필드는 초기값 없이(0, null) 시작합니다. 클래스 초기화(static 블록)가 다시 돌지 않고, 이미 있는 객체의 생성자도 다시 돌지 않기 때문입니다.
  - 새 클래스는 교체할 필요가 없습니다. 처음 쓰일 때 build/classes에서 읽힙니다.
- **아직 로드되지 않은 클래스:** 할 일이 없습니다. 나중에 로드될 때 새 파일을 읽습니다.
- **저장 감지:** `VFS_CHANGES`의 `.java`/`.kt` 내용 변경을 0.5초 디바운스해서 받습니다. "Save All"로 파일 여러 개를 저장해도 한 번만 실행됩니다.
  고른 대상 폴더 안의 파일일 때만 동작하고, 툴윈도우의 "Hotswap on save" 체크로 켜고 끕니다.

검증: 샌드박스 IDE에서 lucid-study-mod 사본에 관찰용 클래스(5초마다 `[SF-PROBE] <message()>` 로그)를 넣고 진행했습니다.
```
PASS probe running (v1 in game log)
PASS fast compile: class path read from Gradle   [최초 1회 13 s, 이후 캐시]
PASS hotswap: method body change applied   [redefined 1, 에이전트 41 ms, 전체 2.4 s (javac)]
PASS hotswap compiled with javac, not Gradle
PASS hotswap on save (VFS change -> swap)  [game log shows v3]
PASS hotswap (JBR): added field + method applied and running   [redefined 1; game log "[SF-PROBE] v4 extra1"]
PASS hotswap (JBR): removed field + method applied              [redefined 1; "[SF-PROBE] v5"]
```
게임 로그는 `v1 ×5 → v2 → v3`로 바뀌었고, **게임은 재시작하지 않았습니다.**

### 보강 (IDE 안 컴파일, Mixin, 리소스, 게임 안 알림)
- **IDE 안에서 컴파일:** javac를 매번 새 프로세스로 띄우지 않고, IDE의 JBR에 들어 있는 javac(`jdk.compiler`, `ct.sym`)로 IDE 안에서 바로 컴파일합니다(`--release`는 프로젝트 것).
  소스셋마다 파일 매니저를 유지해서 Minecraft 클래스패스 jar 색인을 한 번만 읽고, 클래스패스를 받은 직후 백그라운드에서 한 번 미리 컴파일해 둡니다.
  IntelliJ 안에서는 `ToolProvider`가 javac를 못 찾아서(IDE 자체 시스템 클래스 로더), IDE가 실행 옵션으로 열어 둔 `com.sun.tools.javac.api.JavacTool`을 직접 씁니다. 없으면 예전처럼 javac 프로세스로 돌아갑니다.
  결과: 교체 한 번(컴파일 + attach + 교체)이 **0.47~0.86초**, 구조 변경 직후 등은 1.3초 안팎(이전 2.4초, 그 전 Gradle 15초).
- **Mixin:** Run client가 게임에 Mixin 자체의 핫스왑 에이전트(`-javaagent:<클래스패스의 (sponge-)mixin jar>`, `-Dmixin.hotSwap=true`)를 붙입니다. Mixin은 이때 Mixin 클래스마다 같은 이름의 가짜 클래스를 로드해 두고, 그 클래스가 교체되면 Mixin을 다시 읽어 대상 클래스에 다시 적용합니다. 그래서 우리 핫스왑이 Mixin 클래스를 그대로 교체하면 됩니다(로그 "Redefining mixin ...").
- **리소스:** `src/*/resources` 저장을 감지해, Gradle이 알려 준 소스셋의 리소스 출력 폴더(`processResources`가 채우는 곳, dev 게임이 읽는 곳)에 바로 복사하고 에이전트를 부릅니다.
  - `assets/` 아래(텍스처, 모델, 언어, 소리 등): 클라이언트 리소스 다시 불러오기(F3+T와 같음)
  - `data/` 아래(레시피, 루트 테이블, 태그 등): 내장 서버에서 `/reload`. 월드가 열려 있을 때만 됩니다.
  - `fabric.mod.json`, `mods.toml` 같은 모드 메타데이터는 `processResources`가 값을 채워 넣는 파일이라 복사하지 않고 "재시작 필요"로 알립니다.
- **게임 안 알림:** 교체나 리소스 새로고침이 끝나면 핫바 위 액션바에 "Hotswap: 1 class reloaded" / "Resources reloaded (assets)" 같은 줄을 띄웁니다. 재시작이 필요한 클래스가 있으면 노란색으로 알립니다. 월드 안(HUD가 있을 때)에서만 보입니다.
  - 게임 안 알림과 리소스 새로고침은 개발 환경의 이름(Mojang 매핑: NeoForge/Forge 개발 환경과 바닐라 26.x, Yarn: Fabric 개발 환경)으로 Minecraft 클래스를 찾습니다. 난독화된 게임에서는 동작하지 않습니다.

검증(Windows 샌드박스 IDE, lucid-study-mod 사본 1.21.1 Fabric, jbr-21, 시험용 Mixin `SfMixinProbe`를 사본에 추가):
```
PASS hotswap: method body change applied            [864 ms incl. compile (javac in the IDE)]
PASS hotswap compiled in the IDE (warm), whole swap fast
PASS in-game message after a swap                   (에이전트 로그 "in-game message shown: Hotswap: 1 class reloaded")
PASS hotswap: mixin change re-applied to its target ("Redefining mixin kr/kro/lucid/mixin/SfMixinProbe", 로그 m1 -> m2)
PASS resource change -> client reload               (언어 파일 -> "Reloading ResourceManager")
```
데이터 리로드는 월드를 연 게임에 직접 attach해서 확인했습니다("resources: /reload on the integrated server", 서버 스레드 "Loaded 1295 recipes").

### 어노테이션 프로세서와 Kotlin
- **어노테이션 프로세서:** Gradle에서 compileJava의 프로세서 경로(`annotationProcessorPath`)와 생성 소스 폴더를 받아, 빠른 컴파일에서도 돌립니다. Lombok처럼 그게 없으면 컴파일이 안 되는 프로젝트도 됩니다.
  Mixin의 프로세서(refmap 생성기, `fabric-mixin-compile-extensions`, `sponge-mixin`)와 그 `-A` 옵션은 뺍니다. 개발 환경에는 필요 없고, 일부 파일만 컴파일하면 refmap을 그 파일들 것만으로 덮어쓰기 때문입니다.
  프로세서용 클래스 로더는 소스셋마다 하나 만들어 계속 쓰고, 프로세서 객체만 매번 새로 만들어 넘깁니다(`task.setProcessors`). javac가 컴파일마다 jar를 다시 읽으면 Lombok 하나로 1.5초가 걸렸는데, 이렇게 하면 0.15~0.33초입니다.
- **Kotlin:** 예전에는 `.kt`가 바뀌면 Gradle로 컴파일했습니다(10초 이상). 이제 Gradle에서 그 프로젝트가 실제로 쓰는 Kotlin 컴파일러(`kotlinCompilerClasspath`: `kotlin-compiler-embeddable` 등)와 Kotlin 소스·출력 폴더, 클래스패스, `jvmTarget`, 모듈 이름을 받아, `K2JVMCompiler`를 IDE 안의 별도 클래스 로더에서 돌립니다(한 번 로드해 계속 씀, 미리 데우기 포함).
  바뀐 파일은 임시 폴더로 컴파일한 뒤 `.class`만 Kotlin 출력 폴더로 옮깁니다. 일부 파일만 컴파일하면 `META-INF/*.kotlin_module`이 그 파일들 것만으로 덮어써지기 때문입니다.
  Kotlin을 먼저 컴파일하고 Java를 그다음에 컴파일하며, Java 컴파일 클래스패스에 Kotlin 출력을 넣습니다(Gradle의 compileClasspath에는 없음).
  **IDE 안에서는 Kotlin 컴파일러가 실패**해서(컴파일러가 품고 있는 IntelliJ 코어가 IDE 환경을 집어 드는 것으로 보임, IDE 밖에서는 정상) 계속 떠 있는 별도 프로세스(`KotlinServer`, IDE의 JBR로 실행)로 돌립니다. 요청은 표준 입력으로 한 줄씩 보내고, 컴파일러는 그 프로세스에 로드된 채 데워져 있습니다. IntelliJ의 플러그인 클래스 로더는 jar 위치를 알려 주지 않아서, 서버 클래스 파일은 임시 폴더에 풀어 씁니다.
  결과: 미리 데우기 10~12초(백그라운드), 그 뒤 Kotlin 파일 교체 한 번 약 1.8~2.5초(Kotlin과 Java를 함께 컴파일한 경우 포함). Gradle로는 30초 이상 걸렸습니다.
- **고친 버그:** Gradle이 `build/resources`에 쓴 `fabric.mod.json` 같은 빌드 출력도 "저장"으로 받아 재시작을 권하고 있었습니다. `build/`, `.gradle/`, `run/`, `out/` 아래 변경은 이제 무시합니다.

검증(테스트 사본에 Lombok 1.18.38과 Kotlin 2.2.21을 추가, Lombok getter를 쓰는 `SfLombokProbe`, Kotlin `object SfKotlinProbe`):
```
PASS hotswap with an annotation processor (Lombok)        [javac in the IDE]
PASS hotswap of a Kotlin file, compiled by the warm Kotlin server   (게임 로그 "[SF-KOTLIN] k2")
```

### 빠른 재시작 (핫스왑이 못 하는 변경)
새 블록·아이템 등록, 모드 메타데이터, 액세스 와이드너, 빌드 변경처럼 게임이 시작할 때만 읽는 것은 실행 중에 바꿀 수 없습니다. 그래서 **Restart** 버튼이 그 부분을 맡습니다.
1. 에이전트에게 열린 월드의 폴더 이름을 묻고(Mojang `storageSource.getLevelId()` / Yarn `session.getDirectoryName()`, 서버 필드에서 찾음), 정상 종료를 요청합니다. 게임이 월드를 저장하고 꺼질 때까지 기다립니다(90초가 넘으면 강제 종료).
2. runClient를 `--quickPlaySingleplayer <월드>`(1.20+)로 다시 띄웁니다. 월드 이름에 공백이나 한글이 있어도 되게 init script가 Base64로 받아 인자에 붙입니다. 새 게임은 툴윈도우에 다시 붙고 같은 월드로 바로 들어갑니다.

재시작이 필요한 변경을 감지하면 로그와 IDE 알림에 **"Restart into the same world"** 버튼을 띄웁니다.
- 저장한 파일: `fabric.mod.json`, `quilt.mod.json`, `mods.toml`, `*.accesswidener`, `accesstransformer.cfg`, `*mixins.json`, `build.gradle*`, `settings.gradle*`, `gradle.properties`, `libs.versions.toml`
- 핫스왑 결과의 "restart needed"(JBR이 없을 때의 구조 변경)
- **시작할 때 한 번만 도는 코드가 바뀐 경우**(`StartupCode.java`): 교체 전후 클래스에서 `<clinit>`, `onInitialize*`, `register*`, `initialize*`의 명령어를 비교합니다(줄 번호 제외, IntelliJ 내장 ASM). 교체 자체는 성공하지만 이미 실행된 코드라 게임에 반영되지 않는 경우입니다(새 블록 등록 등).

### 엉뚱한 게임을 붙이지 않기
예전에는 Run client 뒤 새로 뜬 마인크래프트 창을 아무거나 붙였습니다. 그사이 런처로 다른 마인크래프트를 켜면 그게 붙을 수 있었습니다.
이제 Run client가 init script로 게임 JVM에 에이전트를 시작 시점부터 붙이고(`-javaagent:...=launch=<실행마다 고유한 파일>`), 에이전트가 그 파일에 자기 PID를 씁니다. 플러그인은 **그 PID의 창만** 붙이고 다른 창은 무시합니다. Linux에서 PID를 모르는 창을 고르던 대체 경로도 없앴습니다. FPS 제한도 게임 시작부터 걸립니다.

검증(Windows 샌드박스, 월드 `SFWorld`에서 시작, Run client 직후 미끼로 바닐라 1.12.2를 따로 띄움):
```
PASS Run client -> game embedded                [game pid 6864]
PASS another Minecraft (decoy) not embedded     [decoy pid 40088 먼저 떴지만 무시]
PASS startup code change -> restart offered     [LucidsCustomPack.onInitialize]
PASS restart -> new game embedded               [38 s]
PASS restart -> back in the same world          [world 'SFWorld']
```

### 한계와 다음 과제
- **구조 변경:** JBR이 있으면 됩니다(위). Java 8/17 게임용 JBR은 시험하지 않았습니다. Attach PID로 붙인 게임은 이미 떠 있는 JVM이라, 직접 JBR과 플래그로 띄운 경우에만 구조 변경이 됩니다.
- **새로 추가한 정적 필드의 초기값, 생성자 변경:** 이미 만들어진 객체와 클래스 초기화에는 반영되지 않습니다.
- **Fabric 환경 어노테이션:** 바뀐 바이트에는 Knot의 `@Environment` 정리가 적용되지 않습니다. 클라이언트 개발 환경에서는 대개 문제가 없습니다.
- NeoForge/Forge, Linux에서의 Mixin·리소스·게임 안 알림은 아직 시험하지 않았습니다.

## Linux (X11)

같은 플러그인이 Linux에서는 `embed/X11Embedder.java`를 씁니다(`GameView.create`가 OS로 고름).
Windows의 `GameEmbedder`와 같은 기능 묶음(`GameView` 인터페이스)이고, X11 호출은 `embed/X11.java`(libX11 FFM)에 모았습니다.
창 조회는 `embed/NativeWindows.java`가 두 OS를 감쌉니다(런처와 자가 테스트가 사용).

- **IDE가 X11로 돌아야 합니다.** Wayland 세션의 IntelliJ는 Wayland 툴킷을 쓰는데, Wayland에는 다른 프로그램의 창을 자식으로 넣는 방법이 없습니다. `-Dawt.toolkit.name=XToolkit`로 실행하면 됩니다. 아니면 Canvas에 안내 문구가 나오고 attach를 거절합니다.
- **임베드:** `XWithdrawWindow` → `override_redirect` 켜기 → 창 관리자가 프레임에서 뺄 때까지 대기 → Canvas 아래로 `XReparentWindow`, 매핑. 창 관리자가 되가져가면 다시 시도하고, 에이전트도 즉시 돌려놓습니다.
- **키보드:** X11에서는 자식 창을 클릭해도 포커스가 옮겨지지 않습니다. 창 관리자들이 쓰는 방식대로 게임 창의 버튼에 패시브 그랩(`XGrabButton`, 동기 모드)을 걸고, 클릭이 오면 `XSetInputFocus` 후 `XAllowEvents(ReplayPointer)`로 그 클릭을 게임에 그대로 넘깁니다. IDE의 다른 곳을 클릭하면 키보드를 AWT로 돌려줍니다.
- **숨김/이동:** 툴윈도우를 숨기거나 옮기기 전에 게임을 루트로 옮기고 매핑을 해제(park)합니다. 다시 보이면 새 Canvas로 넣습니다. 숨김 상태에서는 Windows의 최소화 대신 매핑 해제를 씁니다(자식 창에는 "최소화"가 없음, FPS는 에이전트가 제한). 힙 축소(jcmd)는 같고, 워킹셋 트림은 Windows 전용입니다.
- **IDE 종료:** 셧다운 훅이 `override_redirect`를 끄고 루트로 돌려 일반 창으로 남깁니다. 크래시 때는 에이전트의 X11 구명보트가 월드를 저장하고 게임을 끝냅니다(`poc/embed/README.md`).
- 폴더 탐색, 버전 결정, Run client(`gradlew`), 핫스왑은 OS와 무관한 코드라 그대로 씁니다. Run client와 핫스왑을 Linux IDE에서 실제로 돌려 보지는 않았습니다.

검증(WSL2 Ubuntu 24.04 + WSLg, IntelliJ IDEA 2026.2.3 Linux판 `-Dawt.toolkit.name=XToolkit`, 바닐라 1.21.1, `-Dsulfurframe.autotest.pid`):
```
PASS profile detected                 [FABRIC 1.21.1 0.17.2 (Java 21)]
PASS embedded in the tool window
PASS game sized to the canvas (16:9 fit)
PASS survives floating the tool window
PASS survives re-docking
PASS hidden -> game minimized         (X11: 매핑 해제, state HIDDEN)
PASS shown -> game restored
PASS 16:9 letterbox
F11 fullscreen: not handled on Linux yet (skipped)
PASS open as editor tab
PASS tab closed -> back in the tool window
failures: 0
```
![linux](snapshot-linux.png)

Windows 회귀 확인: 같은 빌드로 Windows 샌드박스 IDE의 Run client + 핫스왑 자동 테스트를 다시 돌려 전부 통과(javac 핫스왑 2.4초).

## 공유 인스턴스 (모드끼리 월드·설정 공유)
패널의 **Shared worlds**를 켜면 같은 마인크래프트 버전의 모든 프로젝트가 게임 폴더 하나를 같이 씁니다(`SharedInstance.java`, 기본은 꺼짐).
- `~/.sulfurframe/instances/<버전>/`에 공용 폴더를 두고, 각 프로젝트 `run/`의 `saves`, `resourcepacks`, `screenshots`, `shaderpacks`를 거기로 연결합니다. Windows는 디렉터리 정션(관리자 권한 불필요), 그 밖에는 심볼릭 링크입니다. `config/`, `logs/`, `mods/`는 프로젝트마다 그대로입니다.
- `options.txt`, `servers.dat`, `hotbar.nbt`는 게임이 통째로 다시 써서 링크가 끊어지므로, 실행 전에 공용 폴더에서 복사해 오고 게임이 끝나면 돌려놓습니다(더 최근 것이 이김).
- 처음 연결할 때 프로젝트에 있던 월드는 공용 폴더로 옮깁니다. 이름이 겹치면 `이름 (프로젝트)`로 보존하고, 아무것도 지우지 않습니다.
- 프로젝트의 버전이 바뀌면 링크만 새 버전 폴더로 바꿉니다. 이전 버전 폴더의 월드는 그대로 남습니다.

검증(임시 홈 폴더, 가짜 프로젝트 두 개): 링크 생성(정션), 겹친 월드 `World1 (projB)` 보존, A의 `options.txt`가 B로 넘어감, 다시 적용해도 변화 없음, 한글·공백 경로, 버전 변경 시 이전 버전 월드 보존.

## 월드 스냅샷과 테스트 월드
- **Snapshot:** 에이전트로 열린 월드 이름을 묻고, 내장 서버에서 `save-all flush`를 실행해 저장을 끝낸 뒤(완료는 파일로 알림), 월드 폴더를 `~/.sulfurframe/snapshots/<버전>/<월드>/<시각>/`에 복사합니다(`WorldSnapshots.java`). `session.lock`은 뺍니다.
- **Restore…:** 월드와 스냅샷을 고르면, 빠른 재시작 흐름으로 게임을 끄고 → 월드 폴더를 스냅샷으로 바꾸고 → 같은 월드로 다시 띄웁니다. 바뀌기 전 월드도 `…-before-restore` 스냅샷으로 남겨서, 되돌리기 자체도 되돌릴 수 있습니다.
- **Test world:** 평지, 크리에이티브, 치트 켬, 평화로움, 낮·날씨 고정, 몹 스폰 끔, 인벤토리 유지 월드를 만들고 바로 들어갑니다(게임이 떠 있으면 재시작해서 들어감). 1.20.5 이상.
  quickPlay는 없는 월드를 만들어 주지 않고, 게임 메뉴로 만들려면 버전·매핑마다 다른 코드가 필요해서, `level.dat`(NBT)를 직접 씁니다(`TestWorld.java`). 오래된 데이터 버전은 게임의 데이터 픽서가 올려 주므로 한 형식으로 1.20.5 이후 전 버전을 맡습니다. 모드 데이터 팩은 게임이 새 팩으로 보고 자동으로 켭니다.
  처음 만든 파일은 "Unknown data version: 0"으로 열리지 않았습니다. `Data.version`은 레벨 포맷 번호(Anvil 19133)이고 게임 버전 묶음은 대문자 `Version`이어서였습니다(바이트코드에서 확인).

검증: 1.21.1(lucid 사본)에서 만든 테스트 월드로 quickPlay → 평지·크리에이티브·낮으로 입장, 모드 데이터 팩 자동 로드("Found new data pack lcd, loading it automatically"). 에이전트의 `world=`/`cmd=save-all flush`/`done=` → 월드 이름 "SF Flat", "ran /save-all flush", 완료 표시.
- **영구 월드와 임시 월드:** **Test world**는 남는 테스트 월드, **Temp world**는 버리는 테스트 월드입니다(`TempWorlds.java`). 임시 월드는 이름이 `[Temp] Test`로 시작하고, 목록(`~/.sulfurframe/temp-worlds.txt`)에 적혀 다음 때 지워집니다: 게임이 끝날 때, 그리고 IDE가 먼저 꺼졌던 경우 다음 플러그인 시작 때.
  안전장치: 게임이 잡고 있는 월드(`session.lock` 잠김)는 건너뛰고, 이름에 `[Temp] ` 표식이 없으면 목록에 있어도 지우지 않습니다. 재시작으로 같은 임시 월드에 다시 들어갈 때는 이전 게임의 종료 정리가 그 월드를 지우지 않도록 잠시 핀을 겁니다.
  검증(임시 홈): 임시 월드 삭제, 핀 걸린 임시 월드 유지, 목록에 잘못 들어간 진짜 월드 `Real` 유지(목록에서만 빠짐).

## 게임 로그, 크래시, 리소스 문제, 디버거, Inspect
- **Game log 탭:** `run/logs/latest.log`를 따라가며 보여 줍니다(`GameLog.java`, `GameLogPanel.java`). 스택 트레이스의 프레임은 IDE의 Java 예외 필터로 소스 링크가 되고, "경고와 오류만" 필터가 있습니다.
- **크래시:** Loom 개발 실행에서는 "Crash report saved to" 줄이 표준 출력에만 나가서, `run/crash-reports` 폴더를 지켜봅니다. 새 보고서가 생기면 원인(Description)과 첫 번째 내 코드 프레임(`knot/`, `app//` 접두어 제거)을 찾아 알림을 띄웁니다: 코드로 가기, 보고서 열기, 재시작.
- **Problems 탭:** 게임이 로그에 남기는 빠진 모델/블록스테이트/텍스처를 목록으로 모읍니다(더블클릭으로 파일 열기). **Check translations**는 블록스테이트와 아이템 모델에 비해 `en_us.json`에 빠진 키를 찾고, **Add missing translations**는 이름을 사람이 읽게 바꿔(`ms_block` → "Ms Block") 넣습니다.
- **Debug:** 켜 두면 Run client가 게임에 JDWP(빈 포트)를 붙여 띄우고, 창이 뜨면 IDE 디버거가 "SulfurFrame: Minecraft" 원격 실행 구성으로 자동 연결합니다(`DebugAttach.java`).
- **Inspect:** 플레이어가 보고 있는 블록(위치, 상태, 블록/블록 엔티티 클래스, NBT)이나 엔티티(클래스, NBT)를 에이전트가 게임 스레드에서 읽어 보여 줍니다(`LookAt.java`). 클래스 이름을 누르면 소스로 갑니다.

검증(1.21.1 lucid 사본, 샌드박스 IDE 자동 테스트): 빠진 모델 2건 보고, 빠진 번역 3개 찾고 3개 추가, 디버거 세션 연결, 일부러 던진 예외로 크래시 → 원인과 `kr.kro.lucid.SfHotswapProbe` 프레임 찾음.

## 임베드된 게임의 마우스 시점 (GLFW, Windows)
키보드는 되는데 마우스로 시점이 안 돌아가던 문제. GLFW는 `glfwSetInputMode(GLFW_CURSOR_DISABLED)`가 불릴 때 창이 "포커스"인지 `GetActiveWindow()`로 확인하는데, 자식 창은 절대 활성 창이 아닙니다. 그래서 클릭으로 포커스를 받은 뒤에 일어난 마우스 잡기(월드 입장, 메뉴 닫기)는 기록만 되고 적용되지 않아, GLFW가 모든 마우스 이동을 버립니다. GLFW는 `WM_SETFOCUS`를 받을 때는 잡기를 적용하므로, 에이전트가 커서 모드가 DISABLED로 바뀌고 게임이 임베드돼 있고 키보드 포커스를 가진 순간에 게임 창에 `WM_SETFOCUS`를 직접 보냅니다(`GlfwCursorFix.java`, 렌더 스레드 = GLFW 스레드에서 실행). 실제 IDE에서 사용자가 확인했습니다.

## Gradle과 게임이 쓰는 Java
- **Gradle 자체의 JDK:** IDE 환경의 `JAVA_HOME`이 게임에 필요한 Java보다 낮으면(26.x용 Loom은 Java 25 Gradle을 요구), 그 버전 이상인 JDK를 찾아 gradlew에 넘깁니다(`GradleJava.java`). 프로젝트가 `org.gradle.java.home`을 정해 두었으면 손대지 않습니다. 클래스패스 스캔과 핫스왑 컴파일도 같은 JDK라 데몬 하나를 같이 씁니다.
- **게임의 JBR:** 실행 태스크의 `javaLauncher`를 툴체인으로 직접 정하는 빌드(예: `javaLauncher.set(javaToolchains.launcherFor { 21 })`)에서는 `executable`만 바꾸면 "Toolchain from `executable` property does not match…", 비우면 "Cannot query … javaLauncher … no value"로 실패합니다. 그래서 JBR이 완전한 JDK(javac 있음)이면 `org.gradle.java.installations.paths`로 알려 주고 `javaLauncher`를 "JetBrains <버전>" 툴체인으로 바꿉니다. init 스크립트는 `afterEvaluate`에서 등록해 빌드 스크립트의 설정보다 나중에 적용됩니다.
- **실행 태스크:** 빌드 스크립트의 Loom 실행 설정(`runConfigs.create("allModsClient")`, `runs { x { client() } }`)을 읽어 클라이언트 설정마다 목록에 따로 띄웁니다(`[runAllModsClient]`). datagen/gametest 설정은 뺍니다. 모든 init 스크립트는 `-PsfRunTask`로 실행하는 그 태스크에만 붙습니다.

검증: buildpali `runClient`/`runAllModsClient`의 launcher가 JBR 21로 잡히고 `runAllModsClient`로 게임이 실제로 뜸(JBR 21, `-XX:+AllowEnhancedClassRedefinition`). Maxwells_Chamber(26.3)는 Java 25 Gradle로 설정 통과, launcher JBR 25.

## 긴 클라이언트 이름
목록에서 넘치는 이름은 `…`로 자르지 않고 흘러갑니다(1.5초 멈춤 → 끝까지 → 멈춤 → 처음으로). 닫힌 목록의 선택 항목과 펼친 목록의 가리킨 줄만 움직이고, 넘칠 때만 다시 그립니다(`MarqueeRenderer.java`).

## Datagen 버튼
**Datagen**을 누르면 프로젝트의 데이터 생성 태스크를 돌리고, 바뀐 생성 파일만 골라 떠 있는 게임에 넘깁니다(`Datagen.java`). 넘기는 길은 저장한 리소스와 같습니다: 게임의 리소스 폴더로 복사한 뒤 `assets/`는 클라이언트 리로드, `data/`는 `/reload`.
- **태스크:** 빌드 스크립트의 실행 설정 중 이름이 데이터인 것(Loom `datagen`, ModDevGradle `data`/`clientData`, NeoGradle `data`)을 쓰고, 없으면 로더 기본값(Fabric `runDatagen`, NeoForge/Forge `runData`)을 씁니다.
- **출력 폴더:** datagen 인자(`-Dfabric-api.datagen.output-dir=${file("…")}`, `--output`, `file("…")`)에서 읽고, 없으면 `src/main/generated`, `src/generated/resources`를 봅니다.
- **바뀐 파일:** 실행 전후 내용(CRC32)을 비교합니다. datagen이 같은 내용으로 다시 써도 바뀐 것으로 치지 않고, datagen 자체 기록인 `.cache`는 뺍니다.
- 생성 폴더는 사용자가 저장한 파일이 아니라서, 끝난 뒤 15초 동안은 저장 감지가 그 폴더를 무시합니다(같은 리로드를 두 번 하지 않게). IDE 파일 목록도 새로고침합니다.
- 생성 폴더가 리소스 폴더(`sourceSets.main.resources.srcDir`)가 아니면 게임에 넘길 수 없어서 재시작하라고 알립니다.

검증(lucid 사본): 생성 파일 하나(`data/c/tags/item/ingots.json`)를 지우고 실행 → `runDatagen` 53초, 바뀐 파일은 그 파일 하나(새 파일 1).
자동 테스트(샌드박스 IDE): 생성 파일 삭제 → Datagen → 그 파일이 다시 생기고 떠 있는 게임이 `data` 리로드(1개 파일). 마우스 클릭 확인만 화면이 가려져 건너뜀.

### 에이전트 jar가 잠겨 있을 때
플러그인은 시작할 때 에이전트를 `%TEMP%\sulfurframe`에 풀어 둡니다. 예전에는 늘 같은 이름(`sf-agent.jar`)이라, 다른 IDE에서 띄운 게임이 그 파일을 잡고 있으면 덮어쓰기가 실패했습니다. 그러면 에이전트가 없어서 "어느 게임이 내 것인지" 표시도 없었고, 그 사이 뜬 다른 Minecraft(테스트의 미끼)를 붙였습니다. 이제 내용으로 이름을 붙이고(`sf-agent-<crc>.jar`) 같은 파일이 있으면 그대로 씁니다. 잠긴 옛 파일은 건드리지 않습니다.

## 개발 모드 (Dev mods…)
개발용 게임마다 있으면 좋은 모드를 프로젝트를 고치지 않고 넣습니다(`DevMods.java`). 실행할 때마다 체크된 모드의 그 마인크래프트 버전·로더용 최신 빌드를 Modrinth에서 받아(필수 의존성 포함, `~/.sulfurframe/devmods/<버전>/`에 보관) Fabric/Quilt 게임에 `-Dfabric.addMods`로 붙입니다. 오프라인이면 받아 둔 파일을 씁니다.

| 모드 | 로더 | 구분 | 용도 |
|---|---|---|---|
| modmenu | Fabric, Quilt | 필수(기본 켬) | 모드 목록과 설정 화면(Fabric에는 기본으로 없음) |
| emi, jei | 모두 / Fabric·NeoForge·Forge | 선택 | 레시피·아이템 확인 |
| jade | 모두 | 선택 | 보고 있는 블록·엔티티와 그 모드 |
| spark | 모두 | 선택 | 프로파일러 |
| mixintrace | Fabric, Quilt | 선택 | 크래시 보고서에 믹스인 정보 |
| sodium | Fabric, Quilt, NeoForge | 선택 | 흔한 렌더러 교체 모드와의 호환 확인(충돌할 수 있음) |

- NeoForge/Forge는 모드 목록과 설정 화면이 기본으로 있어서 필수가 없습니다. 이 로더에서 넣는 방식은 다음 단계입니다(지금은 Fabric/Quilt만).
- WorldEdit은 넣지 않습니다(건축 도구는 BuildPali의 몫).
- 목록 밖의 모드는 Modrinth 이름이나 링크로 추가합니다. **This project only**를 켜면 그 프로젝트만의 목록을 따로 둡니다(예: 충돌하는 모드 끄기).
- 프로젝트가 이미 넣은 모드(빌드 스크립트에 이름이 있거나 클래스패스에 jar가 있음)는 건너뜁니다. Fabric은 같은 모드 id 두 개를 받지 않습니다.

검증: lucid 사본(1.21.1)에 Mod Menu 11.0.5가 로드되고 초기화됨(업데이트 확인까지). Maxwells_Chamber(26.3)는 21.0.0을 고름. BuildPali는 Mod Menu를 직접 넣어서 건너뜀.

## 저절로 전체화면이 되던 문제
임베드한 직후 "game went fullscreen (F11)"로 IDE 밖으로 나가는 일이 모드가 많은 빌드(BuildPali)에서 실행마다 있었습니다. 판정이 창 스타일 `WS_POPUP` 하나였는데, GLFW는 테두리 없는 창의 스타일을 다시 계산할 때도 이 값을 줍니다. 진짜 F11은 창을 모니터 크기로 키우므로(`glfwSetWindowMonitor`), 이제 `WS_POPUP`이면서 모니터를 덮을 때만 전체화면으로 보고, 패널 크기 그대로인 `WS_POPUP`은 자식 창 스타일로 되돌려 IDE 안에 둡니다. 원인 모드는 플러그인 밖(같은 Gradle 명령, PoC 호스트 임베드)에서 재현되지 않아 아직 모릅니다.
