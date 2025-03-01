# IndexedDB

### 特点

- 键值对储存。
- 异步
- 支持事物(transaction)
- 同源限制
- 储存空间大
- 支持二进制

### 基本概念

- 数据库：IDBDatabase 对象
- 对象仓库：IDBObjectStore 对象
- 索引： IDBIndex 对象
- 事务： IDBTransaction 对象
- 操作请求：IDBRequest 对象
- 指针： IDBCursor 对象
- 主键集合：IDBKeyRange 对象

### 打开数据库

```js
const request = window.indexedDB.open("dbname", 1);
// error事件表示打开数据库失败。
request.onerror = function (event) {
  console.log("数据库打开报错");
};
// success事件表示成功打开数据库。
request.onsuccess = function (event) {
  console.log(request.result);
  console.log("数据库打开成功");
};
// 如果指定的版本号，大于数据库的实际版本号，就会发生数据库升级事件upgradeneeded。
request.onupgradeneeded = function (event) {
  console.log(event.target.result);
};
```

### 新建数据库

```js
request.onupgradeneeded = function (event) {
  const db = event.target.result;
  const objectStore = db.createObjectStore("person", { keyPath: "id" });

  // 自动生成主键
  // const objectStore = db.createObjectStore('person', { autoIncrement: true });

  // 创建索引
  objectStore.createIndex("name", "name", { unique: false });
  objectStore.createIndex("email", "email", { unique: true });
};
```

### 新增数据

通过 objectStore 对象的 add()方法，向表格写入一条记录。

```js
function add() {
  var request = db
    .transaction(["person"], "readwrite")
    .objectStore("person")
    .add({ id: 1, name: "张三", age: 24, email: "zhangsan@example.com" });

  request.onsuccess = function (event) {
    console.log("数据写入成功");
  };

  request.onerror = function (event) {
    console.log("数据写入失败");
  };
}
```

### 读取数据

读取数据也是通过事务完成。 objectStore.get()方法用于读取数据，参数是主键的值。

```js
function read() {
  var transaction = db.transaction(["person"]);
  var objectStore = transaction.objectStore("person");
  var request = objectStore.get(1);

  request.onerror = function (event) {
    console.log("事务失败");
  };

  request.onsuccess = function (event) {
    if (request.result) {
      console.log(request.result);
    } else {
      console.log("未获得数据记录");
    }
  };
}
```

### 遍历数据

新建指针对象的 openCursor()方法是一个异步操作，所以要监听 success 事件。

```js
function readAll() {
  var objectStore = db.transaction("person").objectStore("person");

  objectStore.openCursor().onsuccess = function (event) {
    var cursor = event.target.result;

    if (cursor) {
      console.log(cursor);
      cursor.continue();
    } else {
      console.log("没有更多数据了！");
    }
  };
}
```

### 更新数据

put()方法自动更新了主键为 1 的记录。

```js
function update() {
  var request = db
    .transaction(["person"], "readwrite")
    .objectStore("person")
    .put({ id: 1, name: "李四", age: 35, email: "lisi@example.com" });

  request.onsuccess = function (event) {
    console.log("数据更新成功");
  };

  request.onerror = function (event) {
    console.log("数据更新失败");
  };
}
```

### 删除数据

IDBObjectStore.delete()方法用于删除记录。

```js
function remove() {
  var request = db.transaction(["person"], "readwrite").objectStore("person").delete(1);

  request.onsuccess = function (event) {
    console.log("数据删除成功");
  };
}
```

### 使用索引

```js
var transaction = db.transaction(["person"], "readonly");
var store = transaction.objectStore("person");
var index = store.index("name");
var request = index.get("李四");

request.onsuccess = function (e) {
  var result = e.target.result;
  if (result) {
    // ...
  } else {
    // ...
  }
};
```

### html

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
  </head>

  <body>
    <div id="app">
      <button class="add">add</button>
      <button class="get">get</button>
      <button class="getall">getall</button>

      <button class="put">put</button>

      <button class="del">del</button>
    </div>
    <script src="https://code.jquery.com/jquery-3.5.0.min.js"></script>
    <script>
      $(function () {
        let db;

        const request = window.indexedDB.open("dbname", 2);
        // error事件表示打开数据库失败。
        request.onerror = function (event) {
          console.log("数据库打开报错");
        };
        // success事件表示成功打开数据库。
        request.onsuccess = function (event) {
          db = request.result;
          console.log(request.result);
          console.log("数据库打开成功");
        };
        // 如果指定的版本号，大于数据库的实际版本号，就会发生数据库升级事件upgradeneeded。
        request.onupgradeneeded = function (event) {
          console.log(event.target.result);
          db = event.target.result;
          if (!db.objectStoreNames.contains("person")) {
            const objectStore = db.createObjectStore("person", { keyPath: "id" });
            objectStore.createIndex("name", "name", { unique: false });
            objectStore.createIndex("email", "email", { unique: true });
          }
          console.log(111);
        };

        $(".add").click(function () {
          let req = db
            .transaction(["person"], "readwrite")
            .objectStore("person")
            .add({ id: new Date().getTime(), name: "张三", age: 24, email: `${new Date().getTime()}+a@.com` });

          req.onsuccess = function (event) {
            console.log("数据写入成功");
          };

          req.onerror = function (event) {
            console.log("数据写入失败");
          };
        });

        $(".get").click(function () {
          var transaction = db.transaction(["person"]);
          var objectStore = transaction.objectStore("person");
          var req = objectStore.get(1);

          req.onerror = function (event) {
            console.log("事务失败");
          };

          req.onsuccess = function (event) {
            if (req.result) {
              console.log(req.result);
            } else {
              console.log("未获得数据记录");
            }
          };
        });

        $(".getall").click(function () {
          var objectStore = db.transaction(["person"], "readwrite").objectStore("person");

          objectStore.openCursor().onsuccess = function (event) {
            var cursor = event.target.result;

            if (cursor) {
              console.log(cursor);
              cursor.continue();
            } else {
              console.log("没有更多数据了！");
            }
          };
        });

        $(".put").click(function () {
          var req = db.transaction(["person"], "readwrite").objectStore("person").put({ id: 1, name: "李四" });

          req.onsuccess = function (event) {
            console.log("数据更新成功");
          };

          req.onerror = function (event) {
            console.log("数据更新失败");
          };
        });

        $(".del").click(function () {
          var req = db.transaction(["person"], "readwrite").objectStore("person").delete(1587451687741);

          req.onsuccess = function (event) {
            console.log("数据删除成功");
          };
        });
      });
    </script>
  </body>
</html>
```
