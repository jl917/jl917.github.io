# 테스트 베스트 프래틱스

한구간 테스트 코드로 한눈에 목적을 이해할수 있게 해야한다.

## 방법

- 3개부분(무엇을 테스트 할것인가?/어떤 환경에서 진행되는가?/기대하는 결과는?)
- AAA구조로 내용 구성(Arrange/Act/Assert 준비/실행/주장)
- except 요약하기 (Bdd 형식의 expect) https://jestjs.io/docs/expect#expectextendmatchers
- TDD
- UI와 기능 분리(AAA)
- 테스트 케이스 태그(npx jest -t=#dao)

## mock

- 우선 real데이터를 사용, 특정경우에만 mock사용
- mock데이터는 real데이터와 일치
- 도구
  - sinon
  - test double
  - https://www.npmjs.com/package/faker

## type

- 속성 기반 테스트, 여러조합 입력

## DOM Tip

- static한 속성을 활용해서 HTML 요소에 접근

## 주의사항

- public fn에 대해서만 테스트 하고 private fn은 최소한 으로 진행
- Snapshot 요소는 작을수록 좋음.
- 전역에 사용되는 fixtures와seeds는 피하자(매개 테스트는 독립적인 환경에서 실행)
- 에러를 expect 권장, catch금지
- no sleep, 프레임워크내 async 적극활용,(https://testing-library.com/docs/guide-disappearance/)

## 수동도구

- lighthouse https://developers.google.com/web/tools/lighthouse/
- pagespeed https://developers.google.com/speed/pagespeed/insights/

## E2E (https://github.com/puppeteer/puppeteer)

- 몇개 크로스시스템 테스트 케이스 작성.
- 로그인 token을 중복 활용해서 E2E 진행
- E2E Smoke Test를 통해서 사이트맵 한번씩 접근.

## 테스트 보고서

- 테스트 결과를 실시간으로 확인할수 있는 시스템 구축(Storybook) https://storybook.js.org/addons/@storybook/addon-jest
- Coverage 결과를 기준으로 미확인 부분 및 이상한 부분 찾기
- 使用「变异测试」度量逻辑覆盖率???? 4.3
- test코드도 lint를 통해서 코드 품질 향상.
