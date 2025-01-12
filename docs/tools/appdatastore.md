# AppDataStore

### 1. **Realm**

Realm은 기본적으로 오프라인 데이터베이스를 지원하며, 클라우드와 동기화하는 기능도 제공합니다.

- **장점:**
    - 빠르고 안정적인 로컬 데이터베이스.
    - Realm Sync를 통해 서버와 실시간 동기화.
    - Conflict resolution(충돌 해결) 기능 포함.
- **단점:**
    - Realm Sync 사용 시 클라우드 비용 발생.
    - 자체 서버 구현 시 복잡성이 증가.

### 설치:

```bash
bash
코드 복사
npm install realm

```

### 사용 예제:

```jsx
javascript
코드 복사
const Realm = require("realm");

const TaskSchema = {
  name: "Task",
  properties: {
    _id: "int",
    name: "string",
    done: "bool",
  },
  primaryKey: "_id",
};

// 오프라인 데이터베이스
const localRealm = new Realm({ path: "local.realm", schema: [TaskSchema] });

// 온라인 동기화 (Realm Sync)
const app = new Realm.App({ id: "your-realm-app-id" });
const user = await app.logIn(Realm.Credentials.anonymous());

const syncedRealm = await Realm.open({
  schema: [TaskSchema],
  sync: { user, partitionValue: "partition-key" },
});

```

---

### 2. **PouchDB**

PouchDB는 오프라인 우선 데이터베이스로 설계되었으며, CouchDB 또는 기타 호환 가능한 데이터베이스와 동기화할 수 있습니다.

- **장점:**
    - 완전한 오픈 소스.
    - CouchDB와의 간편한 동기화.
    - 브라우저 및 Node.js에서 모두 사용 가능.
- **단점:**
    - 대규모 동기화 시 성능이 낮아질 수 있음.

### 설치:

```bash
bash
코드 복사
npm install pouchdb

```

### 사용 예제:

```jsx
javascript
코드 복사
const PouchDB = require("pouchdb");
PouchDB.plugin(require("pouchdb-find"));

const localDB = new PouchDB("local-db");
const remoteDB = new PouchDB("http://localhost:5984/remote-db");

// 로컬 데이터 추가
await localDB.put({ _id: "1", name: "Task 1", done: false });

// 동기화
localDB.sync(remoteDB, {
  live: true,
  retry: true,
}).on("change", (info) => {
  console.log("Data synchronized:", info);
});

```

---

### 3. **WatermelonDB**

WatermelonDB는 React Native에 특화된 고성능 오프라인 데이터베이스이지만 Node.js에서도 사용할 수 있습니다. 자체 동기화 로직을 작성해야 하지만, 동기화의 유연성을 제공합니다.

- **장점:**
    - 대규모 데이터 처리에 최적화.
    - ORM 스타일로 데이터 관리.
- **단점:**
    - 동기화를 직접 구현해야 함.

### 설치:

```bash
bash
코드 복사
npm install @nozbe/watermelondb

```

### 사용 예제:

```jsx
javascript
코드 복사
const { Database } = require("@nozbe/watermelondb");
const SQLiteAdapter = require("@nozbe/watermelondb/adapters/sqlite");

// SQLite 어댑터
const adapter = new SQLiteAdapter({
  dbName: "watermelon",
  schema: mySchema,
});

// 데이터베이스 초기화
const database = new Database({
  adapter,
  modelClasses: [TaskModel],
});

```

---

### 4. **RxDB**

RxDB는 Reactive Programming 패턴을 사용하는 NoSQL 데이터베이스로, PouchDB를 기반으로 하며 실시간 데이터 동기화와 상태 관리에 적합합니다.

- **장점:**
    - 실시간 데이터베이스로 React, Angular 등과 쉽게 통합 가능.
    - PouchDB 기반으로 동기화 기능 제공.
- **단점:**
    - 러닝 커브가 다소 있음.

### 설치:

```bash
bash
코드 복사
npm install rxdb

```

### 사용 예제:

```jsx
javascript
코드 복사
const { createRxDatabase, addRxPlugin } = require("rxdb");
addRxPlugin(require("