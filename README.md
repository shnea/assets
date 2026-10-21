# Assets

제작법·용어·도구 연결과 이전 버전을 회원별로 보관하는 개인 제작 자산 서비스다. 아이디어 수첩은 자산과 분리하며 MCP로 제공하지 않는다. 관리자는 집계 통계와 용량만 관리한다.

## 실행

1. `.env.example` 형식으로 로컬 `.env`에 플랫폼 개발 환경 값을 입력한다. 키는 서버에만 전달한다.
2. `docker compose -f compose.yml -f compose.proxy.yml up -d --build`
3. 개발 도메인 `https://dev-assets.shnea.kr/`에서 로그인한다. 로컬 Nginx는 localhost와 지정한 LAN 주소의 30170 포트로 연결한다.

웹은 React·TypeScript, API는 Java 21·Spring Boot, 저장소는 PostgreSQL이다. 플랫폼의 OIDC 인증·비공개 영구 파일 API·에디터를 연동한다.

## 확인

```powershell
docker compose -f compose.test.yml up --build --abort-on-container-exit --exit-code-from test
docker compose -f compose.yml -f compose.qa.yml up -d
node qa/smoke.mjs
node qa/mcp-writes.mjs
```

QA는 localhost:30171의 앱과 30172의 가상 인증·파일 서버, 별도 DB로 분리된다. 가상 파일은 메모리에 있으므로 QA를 초기화할 때는 QA DB도 함께 초기화한다. 개발 보관함과 운영에는 QA 설정을 적용하지 않는다.

`qa/codex-mcp.mjs <설치된 codex.js 절대 경로>`는 설치된 Codex의 MCP 클라이언트 초기화와 도구 발견을 확인한다. 모델 작업이나 영구 설정 변경을 하지 않으며 검증용 연결 키는 종료 시 해제한다. `qa/platform-probe.ps1`은 로컬 개발 자격증명으로 플랫폼 파일 계약을 확인하고 그 검사에서 만든 파일만 정리한다.

## 보관과 재사용

- 초안 자동 저장과 명시적인 버전 저장을 구분한다. 저장한 버전과 그 버전의 첨부파일 연결은 유지한다.
- 긴 본문은 플랫폼 에디터 JSON으로 저장한다. 기존 일반 텍스트도 읽는다. 이미지 붙여넣기와 파일 드롭을 지원한다.
- 연결 화면의 MCP 주소와 개인 연결 키를 사용한다. Streamable HTTP와 Bearer 헤더를 지원하는 클라이언트에서 같은 도구를 이용한다. 특정 클라이언트를 선택할 필요가 없다.
- 기존 키와 기본 설정은 다섯 읽기 도구만 제공한다. `자산 등록·버전 추가 허용`을 선택해 만든 키에는 `create_asset`, `add_asset_version`을 추가로 제공한다. 새 자산은 버전 1로 저장하고, 기존 자산의 변경은 과거 내용을 보존하는 새 버전으로 남긴다. 삭제 기능은 제공하지 않는다.
- 스킬 등록은 에이전트가 선택한 `SKILL.md`를 읽고 설명·본문·호출 방법과 도구 정보를 전달하는 방식이다. 서버가 에이전트의 로컬 경로를 열거나 외부 URL을 자동 수집하지 않는다. MCP로 파일을 새로 업로드하는 기능은 제공하지 않는다.
- 쓰기 요청에는 작업별 UUID `requestId`를 사용하고 같은 작업의 재시도에는 동일한 인자를 보낸다. 버전 추가에는 최신 저장 번호 `expectedVersion`과 변경할 필드 `changes`만 전달한다. 생략한 필드는 유지하고 전달한 배열은 전체를 교체한다. 버전 충돌이나 웹 초안이 있으면 덮어쓰지 않고 중단한다.
- 전체 백업 ZIP에는 본문 JSON, Markdown, 이전 버전, 아이디어와 첨부 원본이 들어간다. 가져오기는 현재 회원의 새 복사본을 만들며 기존 자료를 덮어쓰지 않는다.
- 선택한 저장 버전을 `SKILL.md`, 참고 문서, 원본 첨부자료가 포함된 Agent Skills ZIP으로 내보낸다. Markdown·JSON 개별 내보내기도 제공한다.
- 백업 목록 JSON은 최대 16MiB, 원본 파일 하나는 최대 5GB다. 파일 전송은 8MiB 단위로 처리한다. 가져오기는 회원의 남은 파일 용량을 검사한다.

## 배포

개발 PC에서 `./scripts/publish.ps1 -Tag <태그>`로 이미지를 빌드·푸시한다. 운영에는 아래 두 파일과 DB 마운트만 둔다.

- `compose.prod.yml` → `/volume1/docker/prod/myassets/compose.yml`
- 로컬 `.env.prod` → `/volume1/docker/prod/myassets/.env`
- DB → `/volume2/homes/myassets`

`.env.prod`의 운영 플랫폼 값·DB 비밀번호·이미지 태그를 채운 뒤 운영 폴더에서 `docker compose pull`, `docker compose up -d`를 실행한다. 운영용 비밀값 없는 형식은 `deploy/.env.example`, 상세 설정은 [배포 기준](docs/DEPLOYMENT.md)을 참고한다.
