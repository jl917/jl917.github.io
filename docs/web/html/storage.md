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
