#### 函数组件 (Function component)

```jsx
function Greeting(props) {
  return <div>Hi {props.name}!</div>;
}
Greeting.defaultProps = {
  name: "Guest",
};
```

#### 属性解构 (Destructuring props)

```jsx
function Greeting({ name = "dao" }) {
  return <div>Hi {props.name}!</div>;
}
```

#### JSX 中的属性展开 (JSX spread attributes)

```jsx
function Greeting({ name, ...restProps }) {
  return <div {...restProps}>Hi {name}!</div>;
}
```

#### 合并解构属性和其它值 (Merge destructured props with other values)

```jsx
function MyButton(props) {
  return (
    <button
      className="btn"
      {...props}
    />
  );
}
```

#### 条件渲染 (Conditional rendering)

```jsx
// 如果
{
  condition && <span>Rendered when `truthy`</span>;
}
// 除非
{
  condition || <span>Rendered when `falsy`</span>;
}
// 如果-否则
{
  condition ? <span>Rendered when `truthy`</span> : <span>Rendered when `falsy`</span>;
}
```

#### 渲染属性 (Render prop)

```jsx
const Width = ({ children }) => children(500);
<Width>{(width) => <div>window is {width}</div>}</Width>;
```

#### 代理组件 (Proxy component)

```jsx
const Button = props => <button type="button" {...props}>
```

#### 样式组件 (Style component)

```jsx
import classnames from "classnames";
const PrimaryBtn = props => <Btn {...props} primary />;

const Btn = ({ className, primary, ...props }) => (
  <button
    type="button"
    className={classnames("btn", primary && "btn-primary", className)}
    {...props}
  />
);

<PrimaryBtn />
<Btn primary />
```

#### 组织事件 (Event switch)

```jsx
handleEvent({type}) {
  switch(type) {
    case "click":
      return require("./actions/doStuff")(/* action dates */)
    case "mouseenter":
      return this.setState({ hovered: true })
    case "mouseleave":
      return this.setState({ hovered: false })
    default:
      return console.warn(`No case for event type "${type}"`)
  }
}
```

#### 布局组件 (Layout component)

```jsx
<HorizontalSplit
  leftSide={<SomeSmartComponent />}
  rightSide={<AnotherSmartComponent />}
/>;
const HorizontalSplit = ({ leftSide, rightSide }) => (
  <FlexContainer>
    <div>{leftSide}</div>
    <div>{rightSide}</div>
  </FlexContainer>
);
```

#### 容器组件 (Container component)

容器用来获取数据然后渲染到子组件上，仅仅如此。 —Jason Bonta

```jsx
const CommentList = ({ comments }) => (
  <ul>
    {comments.map(comment => (
      <li>
        {comment.body}-{comment.author}
      </li>
    ))}
  </ul>
);

const CommentListContainer = () => {
  useEffect(() => {
    $.ajax({
      url: "/my-comments.json",
      dataType: 'json',
      success: comments =>
        this.setState({comments: comments});
    })
  },[])

  return <CommentList comments={this.state.comments} />
}
```

#### 高阶组件 (Higher-order component)

- 接受一个或多个组件作为输入
- 输出一个组件

```jsx
const WrapContainer = (Component) => {
  return () => (
    <Container>
      <Component />
    </Container>
  );
};
```

#### 受控组件

input 的 value 和 state 同步

#### sub Components

```jsx
const ListGroup = ({ children }) => <ul>{children}</ul>;
const List = () => (
  <Fragment>
    <li>1</li>
    <li>2</li>
  </Fragment>
);

List.group = ListGroup;

const App = () => (
  <List.group>
    <List />
  </List.group>
);
```

#### context

```jsx
const ContextCounter = React.createContext();

const App = () => (
  <ContextCounter.Provider value={0}>
    <Child />
  </ContextCounter.Provider>
);

const Child = () => {
  const count = useContext(ContextCounter);
  return <h1>{count}</h1>;
};
```

#### createPortal

```jsx
import { createPortal } from "react-dom";

const Po = ({ children }) => {
  return createPortal(children, document.getElementById("modal"));
};

export default Po;
```

#### Profiler

```jsx
import React, { Profiler, useState } from "react";

const App = () => {
  const [count, setCount] = useState(0);

  const onRender = (id, phase, actualDuration, baseDuration, startTime, commitTime, interactions) => {
    // id: string - 发生提交的 Profiler 树的 id。 如果有多个 profiler，它能用来分辨树的哪一部分发生了“提交”。
    // phase: "mount" | "update" - 判断是组件树的第一次装载引起的重渲染，还是由 props、state 或是 hooks 改变引起的重渲染。
    // actualDuration: number - 本次更新在渲染 Profiler 和它的子代上花费的时间。 这个数值表明使用 memoization 之后能表现得多好。（例如 React.memo，useMemo，shouldComponentUpdate）。 理想情况下，由于子代只会因特定的 prop 改变而重渲染，因此这个值应该在第一次装载之后显著下降。
    // baseDuration: number - 在 Profiler 树中最近一次每一个组件 render 的持续时间。 这个值估计了最差的渲染时间。（例如当它是第一次加载或者组件树没有使用 memoization）。
    // startTime: number - 本次更新中 React 开始渲染的时间戳。
    // commitTime: number - 本次更新中 React commit 阶段结束的时间戳。 在一次 commit 中这个值在所有的 profiler 之间是共享的，可以将它们按需分组。
    // interactions: Set - 当更新被制定时，“interactions” 的集合会被追踪。（例如当 render 或者 setState 被调用时）。
  };

  return (
    <Profiler
      id="App"
      onRender={onRender}
    >
      <div>{count}</div>
      <button onClick={() => setCount(count + 1)}>sc</button>
    </Profiler>
  );
};

export default App;
```
