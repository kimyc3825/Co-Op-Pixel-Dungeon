# Shattered Pixel Dungeon 원본 업데이트

Co-Op-Pixel-Dungeon은 원본 태그에서 분기한 Git 이력을 보존한다. 웹 여관은 `web-client`에 있고 Windows 클라이언트는 `inn-client`에 있다. Windows 시작, 저장, 길잡이처럼 원본 게임과 연결되는 부분은 `desktop`과 `core`에도 있다.

공식 태그가 나오면 공개 저장소의 최신 Noxac 커밋을 기준으로 병합을 미리 확인한다. PowerShell과 Git 2.38 이상이 필요하다.

```powershell
# 예제의 v4.0.2 대신 검토할 공식 태그를 지정한다.
.\scripts\check-upstream-update.ps1 -Tag v4.0.2 -BaseRef HEAD
```

스크립트는 공식 GitHub 저장소에서 해당 태그를 받아 Git으로 병합을 시험한다. 작업 중인 파일은 그대로 두고 충돌 파일을 출력한다. 이미 포함된 태그는 그 상태를 알려 준다.

충돌이 없으면 같은 명령에 `-ReviewBranch upstream-review`를 붙여 병합 결과를 담은 로컬 검토 브랜치를 만든다. 충돌이 있으면 별도 검토 브랜치에서 출력된 원본 커밋을 일반 Git 병합으로 받아 해당 파일을 해결한다.

검토 브랜치에서 Windows 시작과 기존 저장 슬롯, 길잡이 연습, 웹과 Windows 양방향 파티 플레이를 확인한다. 그 후 [여관 빌드 절차](inn.md)로 새 Windows 배포본을 만든다. 웹 코드와 프로토콜도 바뀌었다면 같은 버전을 Sites에 배포한다.
