# Noxac Pixel Dungeon

Shattered Pixel Dungeon v4.0.2 기반의 Windows·브라우저 협동 플레이 프로토타입입니다. 여관에서 다른 플레이어를 만나고, 두 사람이 같은 던전에서 이동과 평타, 식량, 전리품을 함께 테스트할 수 있습니다.

## 브라우저로 테스트

[웹 여관에서 플레이](https://noxac-pixel-dungeon-inn.kimyc3825.chatgpt.site/play/)

닉네임과 직업, 외관을 고르고 미리 본 뒤 **입장**을 누릅니다. 여관에서 **프로필 변경**을 눌러 다시 고를 수 있습니다. 브라우저와 Windows 0.15.3으로 같은 여관에 들어갈 수 있습니다. 두 클라이언트 모두 파티장과 참가자 역할을 맡을 수 있습니다. 웹에서 혼자 연습하려면 **연습 모드 · 길잡이**을 누릅니다.

두 클라이언트는 같은 직업 스프라이트와 외관 카탈로그를 사용합니다. 선택한 외관은 여관, 길잡이 연습과 파티 원정에 적용됩니다.

여관 지도를 마우스 휠로 확대하거나 축소하고, 경비병과 여관주인 등 다섯 NPC를 클릭해 대화할 수 있습니다. 웹은 여관에 들어올 때 ATB 던전 코드와 스프라이트를 미리 불러오고, 입장할 때 버전을 확인해 브라우저 캐시를 재사용합니다.

여관에서 **하수도**와 **버려진 감옥**을 고를 수 있습니다. 감옥은 원정마다 방과 복도를 생성하고, 두 사람이 같은 지도를 봅니다.

## Windows 테스트

1. [0.15.3 테스트 릴리스](https://github.com/kimyc3825/noxac-pixel-dungeon/releases/tag/inn-v0.15.3)에서 `NoxacPixelDungeon-Inn-0.15.3-win-x64.zip`을 받습니다.
2. ZIP 전체를 푼 뒤 `NoxacPixelDungeon/NoxacPixelDungeon.exe`를 실행합니다. Windows x64용이며 Java 런타임을 포함합니다.
3. 두 사람 모두 0.15.3으로 여관에 접속해 **동료 구함**을 누릅니다. 혼자 연습하려면 **길잡이 연습**을 선택합니다. **1인 원정 · 원본 ATB**를 누르면 원본 캠페인을 실시간 ATB로 플레이합니다. 기존 원본 저장은 **원본 1인**에서 이어갑니다. 원본에서 영구 사망하거나 클리어하면 여관으로 돌아옵니다. 도중에 **저장하고 여관으로**를 눌러 나갔다가 같은 슬롯을 이어할 수도 있습니다.

이동은 WASD, 방향키 또는 숫자패드입니다. 두 방향키를 조합해 대각선으로 이동합니다. **Q**는 평타, **1**은 활 조준, **2**는 식량입니다. **F/I**로 배낭, **H**로 캐릭터 상태, **F1**로 도움말을 엽니다.

여관, 파티 원정, 길잡이 연습에서 **Enter**로 채팅을 열고 다시 **Enter**로 전송합니다. **Esc**로 입력에서 나옵니다. 말풍선이 사라져도 채팅 기록을 스크롤해 볼 수 있습니다.

길잡이 연습 시작 방의 허수아비로 평타와 활을 연습할 수 있습니다. 식량 상자나 회복 도구 칸을 밟아 보급하세요. **1인 원정 · 원본 ATB**는 원본 층, 몬스터, 아이템과 스킬을 사용합니다. **동료 찾기**는 기존 2인 시험 던전으로 이어집니다.

활을 쏘려면 1을 누른 뒤 목표 칸을 클릭합니다. 화살은 첫 벽이나 살아 있는 캐릭터에 닿으면 멈춥니다. 아군 피해는 파티장이 여관에서 설정하며 시작값은 꺼짐입니다.

파티 원정과 길잡이 연습에서 **P**는 최대 60초 작전타임입니다. 채팅과 배낭·상태 조회를 할 수 있습니다. 재개 뒤 60초 동안은 다시 사용할 수 없습니다.

여관은 공용 Sites 서버를 사용합니다. 파티장의 클라이언트에서 원정을 진행하며, 인게임 연결 방식은 던전 화면 상단에 표시됩니다. 파티장이 나가면 원정이 끝납니다.

재현한 버그가 있다면 사용한 버전, 연결 표시, 입력 순서를 함께 남겨 주세요. [테스트 피드백](https://github.com/kimyc3825/noxac-pixel-dungeon/issues)

## 빌드와 소스

- [Windows 빌드, 행동력 규칙, 멀티플레이 프로토콜](docs/inn.md)
- [원본 웹판 빌드와 출처](html/README.md)
- [Sites Worker 로컬 개발 및 배포](inn-server/README.md)
- [원본 Android 빌드](docs/getting-started-android.md), [원본 데스크톱 빌드](docs/getting-started-desktop.md), [원본 iOS 빌드](docs/getting-started-ios.md)

## 원본과 라이선스

[Evan Debenham의 Shattered Pixel Dungeon v4.0.2](https://github.com/00-Evan/shattered-pixel-dungeon/tree/v4.0.2)에서 분기했습니다. Shattered Pixel Dungeon은 [Oleg Dolya의 Pixel Dungeon](https://github.com/watabou/pixel-dungeon)을 바탕으로 합니다.

[GPLv3](LICENSE.txt)와 [서드파티 라이선스 고지](inn-client/THIRD_PARTY_NOTICES.md)가 적용됩니다. Windows 배포본에 대응하는 소스 ZIP은 같은 릴리스에 있습니다.

- [원본 업데이트 검토](docs/upstream-update.md)
