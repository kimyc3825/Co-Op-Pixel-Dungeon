# Noxac Pixel Dungeon

Shattered Pixel Dungeon v4.0.2 기반의 Windows·브라우저 협동 플레이 프로토타입입니다. 여관에서 다른 플레이어를 만나고, 두 사람이 같은 던전에서 이동과 평타, 식량, 전리품을 함께 테스트할 수 있습니다.

## 브라우저로 테스트

[웹 여관에서 플레이](https://noxac-pixel-dungeon-inn.kimyc3825.chatgpt.site/play/)

닉네임을 입력하고 **입장**을 누릅니다. 브라우저와 Windows 0.9.0으로 같은 여관에 들어갈 수 있습니다. 두 클라이언트 모두 파티장과 참가자 역할을 맡을 수 있습니다. 웹에서 혼자 연습하려면 **연습 모드 · 길잡이**을 누릅니다.

여관 지도를 마우스 휠로 확대하거나 축소하고, 경비병과 여관주인 등 다섯 NPC를 클릭해 대화할 수 있습니다. 웹에서는 던전에 입장할 때 실행 코드와 에셋 버전을 확인합니다.

여관에서 **하수도**와 **버려진 감옥**을 고를 수 있습니다. 감옥은 원정마다 방과 복도를 생성하고, 두 사람이 같은 지도를 봅니다.

## Windows 테스트

1. [0.9.0 테스트 릴리스](https://github.com/kimyc3825/noxac-pixel-dungeon/releases/tag/inn-v0.9.0)에서 `NoxacPixelDungeon-Inn-0.9.0-win-x64.zip`을 받습니다.
2. ZIP 전체를 푼 뒤 `NoxacPixelDungeon/NoxacPixelDungeon.exe`를 실행합니다. Windows x64용이며 Java 런타임을 포함합니다.
3. 두 사람 모두 0.9.0으로 여관에 접속해 **동료 구함**을 누릅니다. 혼자 연습하려면 **길잡이 연습**을 선택합니다. **원본 녹픽던**을 누르면 원본 1인 저장 슬롯을 엽니다. 원본에서 영구 사망하거나 클리어하면 여관으로 돌아옵니다. 도중에 **저장하고 여관으로**를 눌러 나갔다가 같은 슬롯을 이어할 수도 있습니다.

이동은 WASD, 방향키 또는 숫자패드입니다. 두 방향키를 조합해 대각선으로 이동합니다. **Space**는 평타, **R**은 활 조준, **E**는 식량, **F1**은 조작 도움말입니다.

활을 쏘려면 R을 누른 뒤 목표 칸을 클릭합니다. 화살은 첫 벽이나 살아 있는 캐릭터에 닿으면 멈춥니다. 아군 피해는 파티장이 여관에서 설정하며 시작값은 꺼짐입니다.

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
