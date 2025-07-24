# React성능 최적화

## Diff Algorithm (VDOM)

#### 1. Tree diff 동일레벨(depth) 요소끼리 비교

```jsx
// 전부 교체
<section>
	<div className="content">hello</div>
</section>
<div className="content">hello</div>
```

#### 2. component 비교

```jsx
// 전부 교체
const About = () => <div>About</div>;
const Main = () => <div>About</div>;

visible ? <About /> : <Main />;
```

#### 3. Element Type 비교

```jsx
// 전부 교체
<section>
	<div className="content">hello</div>
</section>
<div>
	<div className="content">hello</div>
</div>

// Element가 동일하니 다음 단계로 pass
<div>
	<section className="content">hello</section>
</div>
<div>
	<div className="content">hello</div>
</div>

```

#### 4. Element Props 비교

```jsx
// Element가 동일할 경우 props 변경된 부분만 교체, 순서 중요하지 않음. 스타일 obj도 마찬가지.
<div style={{width: 40}} id="a">
	<div className="content">hello</div>
</div>
<div style={{width: 70, height: 30}} id="b">
	<div className="content">hello</div>
</div>

// style값이 동일하더라도 변경은 없지만 내부적으로 변화를 비교함
<div style={{width: 40}}>
	<div className="content">hello</div>
</div>
<div style={{width: 40}}>
	<div className="content">hello</div>
</div>
```

#### 5. key(우선순위 제일 높음.)

```jsx
// key설정 하지 않으면 null로 처리.
<div></div>
<div id="a"></div>

// key 다르면 판단하지 않고 교체.
<div key="1">123</div>
<div key="2">123</div>

// key & type(component, element포함)이 동일한 경우 vdom(fiber) 노드를 그대로 사용. (업데이트와 생성의 차이))
// 전부 교체.
<div key="1">123</div>
<p key="1">123</p>
```

#### 6. 멀티노드 비교

1. 1단계(비교)

```jsx
// newChildNode, oldChildNode 동시 끝나는 경우(Update 처리)
// old
<ul>
<li key="0" className="normal">0</li>
<li key="1" className="normal">1</li>
<li key="2" className="normal">2</li>
</ul>
// new
<ul>
<li key="0" className="bold">0</li>
<li key="1" className="bold">1</li>
<li key="2" className="bold">2</li>
</ul>
//
// newChildNode 순회가 먼저 끝나는 경우(Vdom Delection 처리)
// old
<ul>
<li key="0">0</li>
<li key="1">1</li>
<li key="2">2</li> <!-- Delection 처리 -->
</ul>
// new
<ul>
<li key="0">0</li>
<li key="1">1</li>
</ul>
//
// oldChildNode 순회가 먼저 끝나는 경우(Vdom Placement 처리)
// old
<ul>
<li key="0">0</li>
<li key="1">1</li>
</ul>
// new
<ul>
<li key="0">0</li>
<li key="1">1</li>
<li key="2">2</li> <!-- Placement 처리 -->
</ul>
//
// key가 동일하고 type이 다를 경우 oldChildNode Deletion처리.
// old
<ul>
<li key="0">0</li>
<li key="1">1</li> <!-- Delection 처리 -->
<li key="2">2</li>
</ul>
// new
<ul>
<li key="0">0</li>
<div key="1">1</div>
<li key="2">2</li>
</ul>
//
// oldChildNode, newChildNode모두 남아 있는 경우(key가 다른 부분이 있으면 바로 다음단계.)
// old
<ul>
<li key="0">0</li>
<li key="1">1</li>
<li key="2">2</li>
</ul>
// new
<ul>
<li key="0">0</li>
<li key="2">2</li>
<li key="1">1</li>
</ul>
```

2. 2단계(교체)

```jsx
- 빠른 처리를 위해서 남아있는 oldChildNode를 map객체에 [key]: fiber(vdom) 추가한다.
- placeChild 실행
    1. lastPlacedIndex = 0
    2. oldIndex ≥ lastPlaceIndex 일때 이동하지 않고 lastPlaceIndex = oldIndex로 설정.
    3. oldIndex < lastPlaceIndex 일때 맨 뒤로 이동.
    4. map객체에 관련된 데이터가 없을 경우 Placement
    5. oldChildNode순회후 newChildNode랑 매핑되지 않는 부분은 Deletion 처리

// old
<ul>
<li key="a">a</li>
<li key="b">b</li>
<li key="c">c</li>
<li key="d">d</li>
</ul>
// new
<ul>
<li key="d">d</li>
<li key="a">a</li>
<li key="b">b</li>
<li key="c">c</li>
</ul>
// lastPlacedIndex = 0;
// D가 oldChildNode에서의 index가 3임
// 3 >= lastPlaceIndex 이므로 이동이 필요 없음. lastPlaceIndex = 3으로 지정
// A가 oldChildNode에서의 index가 0임
// 1 < 3(lastPlaceIndex) 이므로 맨뒤로 이동.
// B가 oldChildNode에서의 index가 1임
// 2 < 3(lastPlaceIndex) 이므로 맨뒤로 이동.
// C가 oldChildNode에서의 index가 2임
// 2 < 3(lastPlaceIndex) 이므로 맨뒤로 이동.
```

<br />

## React render하는 조건(bailout조건)

- oldProps === newProps
- context 값의 변화가 있는지?
- workInProgress.type === current.type (only Dev mode, live-reload)
- state변화가 있는지 ?? ~~업데이트 EffectTag가 존재한지, 존재하면 이번 priority 업데이트 task에 포함 되는건지 ?~~

<br/>
<br/>

## 최적화 유형

1. ~~PureComponent,shouldComponentUpdate~~
2. React.memo로 캐시(PureComponent로 만들고 싶을때 사용.)

```jsx
// React는 기본적으로 state변경시 해당 컴포넌트 및 자식 컴포넌트 전부 rerender한다.
// bailout로직중 newProps === oldProps 여야만 컴포넌트를 랜딩하지 않는다.
// 하위 컴포넌트에 React.memo 적용시 알고리즘을 실행해서 랭딩한다.
// 자주 변하는 값은 필요없지만 업데이트가 적거나 static한 컴포넌트는 필요하다.
// 컴포넌트 자체에서 props, state, context변화가 없고 부모의 부모의 .....  key값이 변화가 없는데 rerender된다.

import React, { useState, useMemo } from "react";

const EffectComponent = () => {
  console.log("render effect Component");
  return <>effect Component</>;
};

// const a = <EffectComponent />

// const diff = (prevProps, nextProps) => {
//   console.log(prevProps === nextProps);
//   return prevProps === nextProps;
// }

// const MemoEffectComponent = React.memo(EffectComponent, diff);

const App = () => {
  const [count, setCount] = useState(0); // state또는 context를 건드렸기 때문.
  const onChange = (e) => {
    setCount(count + 1);
  };

  // const b = useMemo(() => <EffectComponent />, [])

  return (
    <>
      <h2>Register</h2>
      <input onChange={onChange} />
      <p>{count}</p>
      <EffectComponent />
      {/* {a} */}
      {/* <MemoEffectComponent /> */}
    </>
  );
};

export default App;
```

3. useMemo, useCallback로 캐시

```jsx
import { useCallback, useState } from "react";

export default function App() {
  const [count, setCount] = useState(0);

  const handleCount = useCallback(() => {
    setCount((count) => count + 1);
  }, []);

  const handleCount2 = useCallback(() => {
    setCount(count + 1);
  }, []);

  return (
    <div className="App">
      <h1>{count}</h1>
      <button onClick={handleCount}>+1</button>
      <button onClick={handleCount2}>+1</button>
    </div>
  );
}
```

4. inlineObject 사용금지

```jsx
// bad
<div style={{width: 200}}></div>
// good
const style = {width:200}
<div style={style}></div>
```

5. 익명함수 사용금지.

```jsx
// bad
<button onClick={() => {...}}>123</button>

// good
const onClick = () => {...};
<button onClick={onClick}>123</button>
```

6. key활용
7. React.Fragment사용

```jsx
// bad
<div>
  <p>1</p>
  <p>2</p>
</div>

// good
<React.Fragment>
  <p>1</p>
  <p>2</p>
<React.Fragment/>
```

8. lazyload(suspense) with webpack
9. transtion(react18)
10. immer 사용(구조공유)
11. useLayoutEffect, useInsertionEffect에 오래 실행되는 스크립트 사용하지 말것.(useEffect와 달리 sync로 실행됨.)

<br/>
<br/>

## React Design

- 변화(props, state, context)랑 불변을 분리해서 처리.

```js
const App = () => {
  const [count, setCount] = useState(0);
  return (
    <>
      {/* Title 컴포넌트 분리 */}
      <h1>h1</h1>

      {/* 이부분 분리 필요 */}
      <p>{count}</p>
      <button onClick={() => setCount(count + 1)}>+1</button>
      {/* 이부분 분리 필요 */}
    </>
  );
};
```

- 자주 랜더되는 컴포넌트를 찾고 그 parent노드를 찾아 가면서 최적화.

<br/>
<br/>

### 시험단계

- **React forget(React without memo)**
- **Offscreen API** https://vuejs.org/guide/built-ins/keep-alive.html#include-exclude

<br/>
<br/>

### 참고링크

- [https://ko.reactjs.org/docs/reconciliation.html#the-diffing-algorithm](https://ko.reactjs.org/docs/reconciliation.html#the-diffing-algorithm)
- https://github.com/facebook/react/blob/bd081376665f5f081dcf4bf72f06b7e563c8046d/packages/react-reconciler/src/ReactChildFiber.old.js#L736
- https://zhuanlan.zhihu.com/p/20346379
- https://react.iamkasong.com/diff/multi.html
