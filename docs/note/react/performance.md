#### 使用 React.Memo 来缓存组件

```jsx
export default React.memo(<Component />)
export default Rect.memo((props) => {
  return <div>{props.value}</div>
})
```

#### 使用 useMemo, useCallback 缓存大量的计算,

```jsx
const Component = () => {
  const val = useMemo(() => 123, []);
  const click = useCallback(() => {
    // balabala~
  });
  return <div onClick={click}>{val}</div>;
};
```

#### 使用 PureComponent

```jsx
function PureComponent(){
  return <div>PureComponent</div>
}
class Component extends React.PureComponent{
  ........
}
```

#### 避免使用内联对象

```jsx
const styles = { margin: 0 };
const aProp = { someProp: 'someValue' }
// Don't do this!
<AnotherComponent style={{ margin: 0 }} aProp={aProp} />
// Do this instead :)
<AnotherComponent style={styles} {...aProp} />
```

#### 避免使用匿名函数

```jsx
// Don't do this!
<AnotherComponent onChange={() => props.callback(props.id)} />
// Do this instead :)
<AnotherComponent onChange={handleChange} />
```

#### 调整 CSS 而不是强制组件加载和卸载

```jsx
// Don't do this!
view === 'view1' ? <SomeComponent /> : <AnotherComponent />
// Do this instead :)
<SomeComponent style={view === 'view1' ? visibleStyles : hiddenStyles}>
<AnotherComponent style={view !== 'view1' ? visibleStyles : hiddenStyles}>
```

#### 使用 React.Fragment 避免添加额外的 DOM

#### 延迟加载不是立即需要的组件

#### Immutable

#### key

#### reselect

#### 使用 React.Memo 来缓存组件

```jsx
export default React.memo(<Component />)
export default Rect.memo((props) => {
  return <div>{props.value}</div>
})
```

#### 使用 useMemo, useCallback 缓存大量的计算,

```jsx
const Component = () => {
  const val = useMemo(() => 123, []);
  const click = useCallback(() => {
    // balabala~
  });
  return <div onClick={click}>{val}</div>;
};
```

#### 使用 PureComponent

```jsx
function PureComponent(){
  return <div>PureComponent</div>
}
class Component extends React.PureComponent{
  ........
}
```

#### 避免使用内联对象

```jsx
const styles = { margin: 0 };
const aProp = { someProp: 'someValue' }
// Don't do this!
<AnotherComponent style={{ margin: 0 }} aProp={aProp} />
// Do this instead :)
<AnotherComponent style={styles} {...aProp} />
```

#### 避免使用匿名函数

```jsx
// Don't do this!
<AnotherComponent onChange={() => props.callback(props.id)} />
// Do this instead :)
<AnotherComponent onChange={handleChange} />
```

#### 调整 CSS 而不是强制组件加载和卸载

```jsx
// Don't do this!
view === 'view1' ? <SomeComponent /> : <AnotherComponent />
// Do this instead :)
<SomeComponent style={view === 'view1' ? visibleStyles : hiddenStyles}>
<AnotherComponent style={view !== 'view1' ? visibleStyles : hiddenStyles}>
```

#### 使用 React.Fragment 避免添加额外的 DOM

#### 延迟加载不是立即需要的组件

#### Immutable

#### key

#### reselect
