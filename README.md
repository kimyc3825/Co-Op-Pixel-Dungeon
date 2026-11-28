# Co-Op-Pixel-Dungeon

Shattered Pixel Dungeon을 바탕으로 만든 웹·Windows 2인 협동 던전입니다.
[여관에서 플레이](https://co-op-pixel-dungeon.sidecat.workers.dev/play/) · [Windows 다운로드](https://github.com/kimyc3825/Co-Op-Pixel-Dungeon/releases) · [빌드와 조작법](docs/inn.md)

## 서버 연결

웹과 Windows 클라이언트, 그리고 여관·채팅·매칭·파티 중계 서버의 소스가 모두 이 저장소에 있습니다. 서버는 [server/](server/)의 Cloudflare Worker입니다.

- 여관 방마다 SQLite 기반 Durable Object 하나가 접속, 이동, 채팅, NPC 알림, 원정 준비와 매칭을 맡습니다. 배치 ID로 방을 나누고, 방이 차면 같은 배치의 다음 방으로 넘깁니다.
- 매칭된 파티마다 Durable Object 하나가 던전 데이터를 받은 순서대로 바로 상대에게 넘깁니다. 어느 쪽이 나가면 원정이 끝납니다.
- 클라이언트는 WebSocket 두 개(`/api/v5/inn`, `/api/v5/link`)로 접속합니다. 메시지와 제한, 종료 코드는 [server/PROTOCOL.md](server/PROTOCOL.md)에 있습니다.
- 웹 여관과 원본 던전 런타임은 같은 Worker의 정적 자산으로 제공합니다.

로컬 실행과 테스트:

```sh
cd server && npm ci
npm run typecheck && npm test          # Durable Object 테스트 (Vitest, Workers 런타임)
node ../scripts/stage-web-client.mjs --lobby-only   # 또는 html:dist 빌드 뒤 인자 없이
npx wrangler dev                        # http://127.0.0.1:8787/play/
```

배포는 [웹 배포](docs/inn.md#웹-배포)를 참고하세요.

[여관 배경 작업과 로컬 미리보기](docs/inn.md#여관-배경-작업)

## 함께 개발하기

`main`에 푸시하면 바로 운영 서버에 배포되므로, 작업은 브랜치와 풀 리퀘스트로 합니다. 절차와 검증 명령은 [개발과 협업 가이드](docs/development.md)에, AI 코딩 에이전트가 지킬 규칙은 [AGENTS.md](AGENTS.md)에 있습니다.

## 원본 프로젝트

[Shattered Pixel Dungeon](https://shatteredpixel.com/shatteredpd/) is an open-source traditional roguelike dungeon crawler with randomized levels and enemies, and hundreds of items to collect and use. It's based on the [source code of Pixel Dungeon](https://github.com/00-Evan/pixel-dungeon-gradle), by [Watabou](https://watabou.itch.io/).

Shattered Pixel Dungeon currently compiles for Android, iOS, and Desktop platforms. You can find official releases of the game on:

[![Get it on Google Play](https://shatteredpixel.com/assets/images/badges/gplay.png)](https://play.google.com/store/apps/details?id=com.shatteredpixel.shatteredpixeldungeon)
[![Download on the App Store](https://shatteredpixel.com/assets/images/badges/appstore.png)](https://apps.apple.com/app/shattered-pixel-dungeon/id1563121109)
[![Steam](https://shatteredpixel.com/assets/images/badges/steam.png)](https://store.steampowered.com/app/1769170/Shattered_Pixel_Dungeon/)<br>
[![GOG.com](https://shatteredpixel.com/assets/images/badges/gog.png)](https://www.gog.com/game/shattered_pixel_dungeon)
[![Itch.io](https://shatteredpixel.com/assets/images/badges/itch.png)](https://shattered-pixel.itch.io/shattered-pixel-dungeon)
[![Github Releases](https://shatteredpixel.com/assets/images/badges/github.png)](https://github.com/00-Evan/shattered-pixel-dungeon/releases)

If you like this game, please consider [supporting me on Patreon](https://www.patreon.com/ShatteredPixel)!

There is an official blog for this project at [ShatteredPixel.com](https://www.shatteredpixel.com/blog/).

The game also has a translation project hosted on [Transifex](https://explore.transifex.com/shattered-pixel/shattered-pixel-dungeon/).

Note that **this repository does not accept pull requests!** The code here is provided in hopes that others may find it useful for their own projects, not to allow community contribution. Issue reports of all kinds (bug reports, feature requests, etc.) are welcome.

If you'd like to work with the code, you can find the following guides in `/docs`:
- [Compiling for Android.](docs/getting-started-android.md)
    - **[If you plan to distribute on Google Play please read the end of this guide.](docs/getting-started-android.md#distributing-your-app)**
- [Compiling for desktop platforms.](docs/getting-started-desktop.md)
- [Compiling for iOS.](docs/getting-started-ios.md)
- [Recommended changes for making your own version.](docs/recommended-changes.md)
