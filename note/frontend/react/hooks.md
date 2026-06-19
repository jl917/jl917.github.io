# Hooks

### useState

```jsx
const App = () => {
  const [count, setCount] = useState(0);
  return <h1 onClick={() => setCount(count + 1)}>{count}</h1>;
};
```

### useReducer

```jsx
const initialState = { count: 0 };

function reducer(state, action) {
  switch (action.type) {
    case "increment":
      return { count: state.count + 1 };
    case "decrement":
      return { count: state.count - 1 };
    default:
      throw new Error();
  }
}

function Counter() {
  const [state, dispatch] = useReducer(reducer, initialState);
  return (
    <>
      Count: {state.count}
      <button onClick={() => dispatch({ type: "decrement" })}>-</button>
      <button onClick={() => dispatch({ type: "increment" })}>+</button>
    </>
  );
}
```

### useContext

```jsx
import { createContext, useContext } from "react";

const Context = createContext({ theme: "light" });

const Child = () => {
  const { theme } = useContext(Context);
  return <>{theme}</>;
};

const Parent = () => {
  return <Child />;
};

const App = () => {
  return (
    <Context.Provider value={{ theme: "dark" }}>
      <Parent />
    </Context.Provider>
  );
};

export default App;
```

## effect관련

### useEffect

```jsx
const App = () => {
  useEffect(() => {
    console.log(111);
  }, []);
  return <div>title</div>;
};
```

### useLayoutEffect

```jsx
const App = () => {
  useLayoutEffect(() => {
    console.log(111);
  }, []);
  return <div>title</div>;
};
```

### useInsertionEffect

```jsx
const App = () => {
  useInsertionEffect(() => {
    console.log(111);
  }, []);
  return <div>title</div>;
};
```

### useEffectEvent

```jsx

```

## ref관련

### useRef

```jsx
import { useRef, forwardRef } from "react";

const Child = forwardRef((props: any, ref: any) => {
  return <input ref={ref} />;
});

const Parent = () => {
  const inputRef = (useRef < HTMLInputElement) | (null > null);
  const onClick = () => {
    if (inputRef.current) {
      inputRef.current.value = "reset";
    }
  };
  return (
    <>
      <Child ref={inputRef} />
      <button onClick={onClick}>reset</button>
    </>
  );
};

const App = () => {
  return <Parent />;
};

export default App;
```

### useImperativeHandle

```jsx

```

## 성능관련

### useMemo

```jsx
const App = () => {
  const title = useMemo(() => "dao", []);
  return <div>{title}</div>;
};
```

### useCallback

```jsx
const App = () => {
  const setTitle = useCallback(() => {
    console.log(111);
  }, []);
  return (
    <button
      type="button"
      onClick={setTitle}
    >
      +
    </button>
  );
};
```

### useMemoCache

```jsx

```

## 디버깅관련

### useDebugValue

```jsx

```

## concurrent 동시성모드

### useTransition

```jsx
const App = () => {
  const [value, setValue] = useState("");
  const [isPending, startTransition] = useTransition();

  const onChange = (e: ChangeEvent<HTMLInputElement>) => {
    // 긴급처리건.
    setValue(e.target.value);
    startTransition(() => {
      // 후순위 처리.
      setContent(e.target.value);
    });
  };
  return (
    <>
      <input
        value={value}
        onChange={onChange}
      />
      <div>{value.length}</div>
    </>
  );
};
```

### useDeferredValue

```jsx
const App = () => {
  const [value, setValue] = useState("");
  const lowValue = useDeferredValue(value.length);

  const onChange = (e: ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };

  return (
    <>
      <input
        value={value}
        onChange={onChange}
      />
      <div>{lowValue}</div>
    </>
  );
};
```

### useSyncExternalStore(useMutableSource)

```jsx
// todoStore.js
let nextId = 0;
let todos = [{ id: nextId++, text: "Todo #1" }];
let listeners = [];

export const todosStore = {
  addTodo() {
    todos = [...todos, { id: nextId++, text: "Todo #" + nextId }];
    emitChange();
  },
  subscribe(listener) {
    listeners = [...listeners, listener];
    return () => {
      listeners = listeners.filter((l) => l !== listener);
    };
  },
  getSnapshot() {
    return todos;
  },
};

function emitChange() {
  for (let listener of listeners) {
    listener();
  }
}

// App.jsx
const App = () => {
  const todos = useSyncExternalStore(todosStore.subscribe, todosStore.getSnapshot);
  return (
    <>
      <button onClick={todosStore.addTodo}>Add todo</button>
      <hr />
      <ul>
        {todos.map((todo) => (
          <li key={todo.id}>{todo.text}</li>
        ))}
      </ul>
    </>
  );
};
```

## RSC(React Server Component)

### useId

```jsx
// 여러번 불러오는 컴포넌트일 경우 아래와 같이 필요함.
const App = () => {
  const inputId = useId();

  return (
    <>
      <input id={`${inputId}-firstName`} />
      <input id={`${inputId}-lastName`} />
    </>
  );
};
// ssr일경우 생성한 id가 다른경우가 있음.
// 1. 서버단 생성시 id="1" 생성
// 2. <div id="1"></div>을 client에게 전달
// 3. csr랭딩시 hydrate를 통해서 id="2" 로 처리.
```

### use

```jsx

```

### useCacheRefresh

```jsx

```

### useOptimistic

```jsx

```

### useFormStatus(react-dom)

```jsx

```
