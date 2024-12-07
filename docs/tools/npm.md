# Npm

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

### package

- https://www.npmjs.com/package/jsbarcode
- https://www.npmjs.com/package/qrcode
- https://www.npmjs.com/package/dexie
- https://www.npmjs.com/search?q=node-schedule
- https://www.npmjs.com/package/numeral
- https://www.npmjs.com/package/currency.js