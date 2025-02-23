# Web Storage

## localStorage, sessionStorage

```js
localStorage.setItem("myCat", "Tom");
localStorage.getItem("myCat");
localStorage.removeItem("myCat");
localStorage.clear();
```

## setItem 값 모니터링

```js
const orignalSetItem = localStorage.setItem;

localStorage.setItem = function (key, newValue) {
  let setItemEvent = new Event("setItemEvent");
  setItemEvent.value = localStorage.getItem(key); // 필요에 따라 사용.
  setItemEvent.newValue = newValue;
  setItemEvent.key = key;
  window.dispatchEvent(setItemEvent);
  orignalSetItem.apply(this, arguments);
};

window.addEventListener("setItemEvent", function (e) {
  console.log(e);
});
```

### cookie

```js
// 读取Cookie
document.cookie;

// 基本
document.cookie = "name=Raymond";
// 动态使用
document.cookie = "name=" + encodeURIComponent(name);
// 创建2个cookie
document.cookie = "name=Raymond";
document.cookie = "age=43";
// 设置过期
document.cookie = "name=Raymond; expires=Fri, 31 Dec 9999 23:59:59 GMT";
// 设置子域名访问
document.cookie = "name=Raymond; domail=app.guryong.cc";

// 删除Cookie 只需把时间设置为过去的时间
document.cookie = "name=Raymond; expires=Thu, 01 Jan 1970 00:00:00 GMT";
```
