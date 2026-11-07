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
버전 감지기 fixture 테스트:
```bash
javac -d out sf-idea-plugin/src/main/java/dev/sulfurframe/idea/profile/ProjectProfile.java sf-idea-plugin/src/test/java/dev/sulfurframe/idea/profile/ProfileSelfTest.java
java -cp out dev.sulfurframe.idea.profile.ProfileSelfTest sf-idea-plugin/src/test/resources/profiles [실제 프로젝트 폴더...]
```
fixture 6개(NeoForge MDK, Forge 1.20.1 MDK, Forge FG2 1.12.2, Quilt, kts 직접 선언, 버전 카탈로그)와 로컬 실제 프로젝트 4개를 정확히 감지했습니다.

## 아직 안 된 것
- **"Run client" 버튼으로 실제 모드 프로젝트의 `runClient`를 실행해서 붙이는 흐름은 검증하지 않았습니다.** 샌드박스 테스트는 이미 떠 있는 게임에 붙이는 경로만 확인했습니다.
- 여러 버전·여러 로더를 한 빌드에 담은 프로젝트(예: buildpali)는 "알 수 없음"으로 나옵니다. `detectAll`로 하위 프로젝트를 고르는 UI가 필요합니다.
- 모드마다 빌드와 월드가 따로인 불편(공유 인스턴스/월드), Gradle 플러그인 기반 정식 빌드, 마켓플레이스 배포 형태는 아직입니다.
