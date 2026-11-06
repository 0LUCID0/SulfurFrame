# SulfurFrame — 프로필·월드 관리 (PoC)

목표는 모든 마인크래프트 클라이언트(공식 런처, Prism, CurseForge, Modrinth, 모드 개발 프로젝트)에 흩어진 월드, 키 설정, 옵션을 다루는 것입니다.
계층은 세 개로 나눕니다.

1. **개인 프로필(평소 플레이):** 키 설정과 옵션을 병합 동기화하고, 서버 목록·리소스팩·셰이더팩·스크린샷은 하나로 묶고, 월드는 라이브러리로 다룹니다.
2. **개발 작업 공간:** 마인크래프트 버전별 공용 개발 홈, 테스트 월드 템플릿, 스냅샷. 개인 프로필과 격리됩니다.
3. **다리:** 실제 월드의 복사본으로 개발 빌드를 시험하고, 빌드를 인스턴스에 배포합니다. 원본은 읽기만 합니다.

## 1단계: 인스턴스 스캐너 (`InstanceScanner.java`)

읽기 전용입니다. 아무것도 바꾸지 않습니다.

```bash
java InstanceScanner.java --dev-root "C:/path/to/workspace" [--json out.json]
```

| 출처 | 읽는 것 |
|---|---|
| 공식 런처 | `%APPDATA%/.minecraft/launcher_profiles*.json`의 설치별 `gameDir` (같은 폴더는 하나로 합침) |
| Prism | `prismlauncher.cfg`의 `InstanceDir`, 인스턴스별 `instance.cfg`, `mmc-pack.json`(마인크래프트 버전, 로더) |
| CurseForge | `~/curseforge/minecraft/Instances/*/minecraftinstance.json` |
| Modrinth | `%APPDATA%/ModrinthApp/profiles/*`. 메타데이터가 `app.db`(SQLite)라서 버전과 로더는 인스턴스의 로그·`options.txt`에서 추정 |
| 개발 프로젝트 | `gradle.properties`의 `minecraft_version`과 `run/`(또는 `runs/client`) |

인스턴스마다 게임 폴더, 마인크래프트 버전, 로더, 모드 수, `options.txt`의 데이터 버전과 키 설정 수, 월드 목록을 보여 줍니다.
월드 정보는 `level.dat`을 직접 읽은 이름, **마지막으로 연 버전**(`Version.Name`, `DataVersion`), 마지막 플레이 날짜입니다.

- 로그는 UTF-8이 아닐 수 있어서 바이트 그대로 읽습니다. 로더 인식 형식은 `Loading Minecraft X with Fabric Loader Y`, `forge-<mc>-<ver>`, `neoforge-…`입니다.
- **인스턴스보다 새 버전으로 저장된 월드는 ⚠로 표시합니다.** 이 PC에서도 하나 발견했습니다. Modrinth `my`(1.21.1) 안에 1.21.4로 마지막 저장된 월드가 있습니다.
- 계정 이름 같은 개인 정보는 출력하지 않습니다.

### 이 PC 결과 (2026-11-07)
인스턴스 12개(공식 1, Prism 1, Modrinth 6, 개발 4), 월드 8개. CurseForge 인스턴스는 0개. 공식 런처는 버전을 `options.txt` 데이터 버전(4671)으로만 알 수 있습니다.

## 다음 단계
2. 키 설정·옵션 병합 동기화 (javaagent `premain`에서 병합, 종료 훅에서 변경분 회수)
3. 실제 월드 스냅샷으로 개발 빌드 시험, 빌드를 인스턴스에 배포
4. 개발 공용 홈(정션)과 테스트 월드 템플릿
