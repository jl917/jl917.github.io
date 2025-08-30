# Npm

### install 팁

install 시 https://registry.npmjs.org/jnpkg 여기서 다운로드

### 동일패키지 여러 버전 공존

```sh
npm install --save lodash-v1@npm:lodash@1.0.0

import a from 'lodash-v1';
import b from 'lodash';
```

### 기본설치된 package 리스트

```sh
npm list --depth=0
npm list --depth=0 -global
```

### 배포된 패키지 제거하기

```sh
npm unpublish dzmtest@1.0.1 --force # 특정 버정만 제거
npm unpublish dzmtest --force # 패지지 제거
npm unpublish --force # package.json에 지정된 패키지 제거
```

### pnpm 명령어

| 카테고리         | 명령어                                 | 설명                            |
| ---------------- | -------------------------------------- | ------------------------------- |
| **기본**         | `pnpm --version`                       | 버전 확인                       |
|                  | `pnpm init`                            | 프로젝트 초기화                 |
| **설치**         | `pnpm install` (`pnpm i`)              | 의존성 설치                     |
|                  | `pnpm add <패키지>`                    | 패키지 설치                     |
|                  | `pnpm add <패키지> -D`                 | 개발 의존성 설치                |
|                  | `pnpm add <패키지> -g`                 | 전역 설치                       |
|                  | `pnpm update`                          | 의존성 업데이트                 |
| **제거**         | `pnpm remove <패키지>`                 | 패키지 삭제                     |
| **실행**         | `pnpm run <script>`                    | 스크립트 실행                   |
|                  | `pnpm exec <명령어>`                   | 로컬 바이너리 실행              |
| **관리**         | `pnpm list` (`pnpm ls`)                | 패키지 목록                     |
|                  | `pnpm outdated`                        | 업데이트 확인                   |
|                  | `pnpm prune`                           | 불필요한 패키지 제거            |
|                  | `pnpm audit`                           | 보안 점검                       |
|                  | `pnpm why <패키지>`                    | 설치 이유 확인                  |
| **워크스페이스** | `pnpm init -w`                         | 워크스페이스 초기화             |
|                  | `pnpm add <패키지> -w`                 | 전체 워크스페이스에 추가        |
|                  | `pnpm recursive install` (`pnpm i -r`) | 모든 워크스페이스 설치          |
|                  | `pnpm recursive run <script>`          | 모든 워크스페이스 스크립트 실행 |
| **전역**         | `pnpm add -g <패키지>`                 | 전역 설치                       |
|                  | `pnpm remove -g <패키지>`              | 전역 제거                       |
|                  | `pnpm list -g`                         | 전역 패키지 목록                |
| **캐시/설정**    | `pnpm store path`                      | 캐시 저장소 경로                |
|                  | `pnpm store prune`                     | 캐시 정리                       |
|                  | `pnpm config get <key>`                | 설정 값 확인                    |
|                  | `pnpm config set <key> <value>`        | 설정 값 변경                    |

### yarn 명령어

| 카테고리         | 명령어                         | 설명                            |
| ---------------- | ------------------------------ | ------------------------------- |
| **기본**         | `yarn --version`               | 버전 확인                       |
|                  | `yarn init`                    | 새 프로젝트 초기화              |
|                  | `yarn init -y`                 | 기본 설정으로 초기화            |
| **설치**         | `yarn install` (`yarn`)        | 의존성 설치                     |
|                  | `yarn add <패키지>`            | 패키지 설치                     |
|                  | `yarn add <패키지> --dev`      | 개발 의존성 설치                |
|                  | `yarn global add <패키지>`     | 전역 설치                       |
|                  | `yarn upgrade`                 | 의존성 업데이트                 |
| **제거**         | `yarn remove <패키지>`         | 패키지 삭제                     |
| **실행**         | `yarn run <script>`            | 스크립트 실행                   |
|                  | `yarn <script>` (단축)         | `run` 생략 가능                 |
| **관리**         | `yarn list`                    | 설치된 패키지 목록              |
|                  | `yarn outdated`                | 업데이트 가능 패키지 확인       |
|                  | `yarn cache clean`             | 캐시 삭제                       |
|                  | `yarn why <패키지>`            | 특정 패키지 설치 이유 확인      |
| **워크스페이스** | `yarn workspaces info`         | 워크스페이스 정보 출력          |
|                  | `yarn workspace <이름> <명령>` | 특정 워크스페이스에서 명령 실행 |
| **전역**         | `yarn global add <패키지>`     | 전역 설치                       |
|                  | `yarn global remove <패키지>`  | 전역 제거                       |
|                  | `yarn global list`             | 전역 패키지 목록                |
