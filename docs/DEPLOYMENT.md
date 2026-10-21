# 배포 기준

- 개발 도메인: https://dev-assets.shnea.kr/
- 운영 도메인: https://assets.shnea.kr/
- 로컬 외부 진입점: Nginx, 30170 포트. 추가 외부 포트가 필요하면 30171부터 순차 사용한다.
- 개발 검증과 운영 실행은 Docker를 기본으로 한다.
- 이미지 이름: `registry.shnea.kr/assets-<컨테이너명>:<태그>`.
- 예시: `registry.shnea.kr/assets-web:dev`, `registry.shnea.kr/assets-api:dev`, `registry.shnea.kr/assets-nginx:dev`.
- 컨테이너 구성: nginx → web / api, 서비스 전용 db. DB는 외부 포트를 열지 않는다.

## 개발 도메인 연결

현재 개발 PC의 LAN 주소는 `192.168.0.55`이고 서비스 진입점은 `30170`이다.

1. NAS의 Nginx Proxy Manager에서 `dev-assets.shnea.kr`을 `http://192.168.0.55:30170`으로 연결한다. 해당 호스트의 유효한 HTTPS 인증서를 적용한다.
2. 플랫폼 DEV 환경의 로그인 허용 콜백에 `https://dev-assets.shnea.kr/callback`을 등록한다. 로그아웃 복귀 주소는 `https://dev-assets.shnea.kr/`이다.
3. `.env`의 `APP_URL`, `PLATFORM_OIDC_REDIRECT_URI`, `OIDC_POST_LOGOUT_REDIRECT_URI`를 개발 도메인에 맞추고 `COOKIE_SECURE=true`, `ASSETS_LAN_ADDRESS=192.168.0.55`를 적용한다.
4. `docker compose -f compose.yml -f compose.proxy.yml up -d`로 반영한다. localhost와 지정한 LAN 주소만 30170을 받는다. HTTPS에서 로그인·로그아웃을 확인한다.

운영은 운영용 플랫폼 프로젝트·환경·서버 키와 도메인으로 별도 설정한다. 개발 자격증명이나 QA 설정을 운영으로 복사하지 않는다.

## 분리된 검증 환경

`docker compose -f compose.yml -f compose.qa.yml up -d`는 별도 DB와 가상 OIDC/파일 서버로 실행한다. 앱은 `http://localhost:30171`, 가상 로그인은 `http://localhost:30172`다. 실제 계정과 분리된 Alice/Bob/운영 관리자 테스트 계정을 사용한다. `compose.qa.yml`은 배포에 사용하지 않는다.

## 운영 서버에 둘 파일

개발 PC의 `compose.prod.yml`을 `/volume1/docker/prod/myassets/compose.yml`로, 비밀값을 채운 `.env.prod`를 같은 폴더의 `.env`로 복사한다. DB 경로는 `ASSETS_DB_PATH=/volume2/homes/myassets`다. `.env.prod`는 실제 값이 들어갈 로컬 파일로 Git에서 제외한다. 비밀값 없는 형식은 `deploy/.env.example`을 참고한다.

개발 PC에서 검증 후 `./scripts/publish.ps1 -Tag 0.1.1`으로 세 이미지를 빌드·푸시한다. 운영의 `.env`에서 `ASSETS_IMAGE_TAG`를 같은 태그로 맞춘 뒤 `/volume1/docker/prod/myassets`에서 `docker compose pull`과 `docker compose up -d`로 실행한다. 운영용 플랫폼 환경·키와 DB 비밀번호는 운영 파일에 입력해야 한다. 이 이미지의 대상은 Linux amd64다.

이미 운영 중이면 `.env`의 이미지 태그만 바꾸며 기존 운영 자격증명과 DB 경로를 유지한다. `0.1.1`은 MCP 등록·버전 추가 권한과 중복 요청 기록을 추가하는 DB 마이그레이션을 API 시작 시 적용한다. 기존 연결 키는 읽기 전용으로 유지되므로 등록 기능을 쓰려면 웹의 에이전트 연결에서 권한을 선택해 새 키를 만든 뒤 클라이언트에 교체한다.

운영 Compose에는 소스 경로나 빌드 명령이 없다. 웹·API·Nginx 설정은 레지스트리 이미지에 포함한다. 첨부 원본은 플랫폼에만 저장하고 Assets의 영속 마운트는 PostgreSQL 데이터 하나로 구성한다.
