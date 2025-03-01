"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["7661"],{670:function(e,n,t){t.r(n),t.d(n,{default:()=>c});var s=t(5893),r=t(65);function a(e){let n=Object.assign({h1:"h1",a:"a",h3:"h3",pre:"pre",code:"code",h2:"h2"},(0,r.ah)(),e.components);return(0,s.jsxs)(s.Fragment,{children:[(0,s.jsxs)(n.h1,{id:"hooks",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#hooks",children:"#"}),"Hooks"]}),"\n",(0,s.jsxs)(n.h3,{id:"usestate",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usestate",children:"#"}),"useState"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:"const App = () => {\n  const [count, setCount] = useState(0);\n  return <h1 onClick={() => setCount(count + 1)}>{count}</h1>;\n};\n"})}),"\n",(0,s.jsxs)(n.h3,{id:"usereducer",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usereducer",children:"#"}),"useReducer"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'const initialState = { count: 0 };\n\nfunction reducer(state, action) {\n  switch (action.type) {\n    case "increment":\n      return { count: state.count + 1 };\n    case "decrement":\n      return { count: state.count - 1 };\n    default:\n      throw new Error();\n  }\n}\n\nfunction Counter() {\n  const [state, dispatch] = useReducer(reducer, initialState);\n  return (\n    <>\n      Count: {state.count}\n      <button onClick={() => dispatch({ type: "decrement" })}>-</button>\n      <button onClick={() => dispatch({ type: "increment" })}>+</button>\n    </>\n  );\n}\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"usecontext",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usecontext",children:"#"}),"useContext"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'import { createContext, useContext } from "react";\n\nconst Context = createContext({ theme: "light" });\n\nconst Child = () => {\n  const { theme } = useContext(Context);\n  return <>{theme}</>;\n};\n\nconst Parent = () => {\n  return <Child />;\n};\n\nconst App = () => {\n  return (\n    <Context.Provider value={{ theme: "dark" }}>\n      <Parent />\n    </Context.Provider>\n  );\n};\n\nexport default App;\n'})}),"\n",(0,s.jsxs)(n.h2,{id:"effect관련",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#effect관련",children:"#"}),"effect관련"]}),"\n",(0,s.jsxs)(n.h3,{id:"useeffect",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useeffect",children:"#"}),"useEffect"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:"const App = () => {\n  useEffect(() => {\n    console.log(111);\n  }, []);\n  return <div>title</div>;\n};\n"})}),"\n",(0,s.jsxs)(n.h3,{id:"uselayouteffect",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#uselayouteffect",children:"#"}),"useLayoutEffect"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:"const App = () => {\n  useLayoutEffect(() => {\n    console.log(111);\n  }, []);\n  return <div>title</div>;\n};\n"})}),"\n",(0,s.jsxs)(n.h3,{id:"useinsertioneffect",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useinsertioneffect",children:"#"}),"useInsertionEffect"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:"const App = () => {\n  useInsertionEffect(() => {\n    console.log(111);\n  }, []);\n  return <div>title</div>;\n};\n"})}),"\n",(0,s.jsxs)(n.h3,{id:"useeffectevent",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useeffectevent",children:"#"}),"useEffectEvent"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h2,{id:"ref관련",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#ref관련",children:"#"}),"ref관련"]}),"\n",(0,s.jsxs)(n.h3,{id:"useref",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useref",children:"#"}),"useRef"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'import { useRef, forwardRef } from "react";\n\nconst Child = forwardRef((props: any, ref: any) => {\n  return <input ref={ref} />;\n});\n\nconst Parent = () => {\n  const inputRef = (useRef < HTMLInputElement) | (null > null);\n  const onClick = () => {\n    if (inputRef.current) {\n      inputRef.current.value = "reset";\n    }\n  };\n  return (\n    <>\n      <Child ref={inputRef} />\n      <button onClick={onClick}>reset</button>\n    </>\n  );\n};\n\nconst App = () => {\n  return <Parent />;\n};\n\nexport default App;\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"useimperativehandle",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useimperativehandle",children:"#"}),"useImperativeHandle"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h2,{id:"성능관련",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#성능관련",children:"#"}),"성능관련"]}),"\n",(0,s.jsxs)(n.h3,{id:"usememo",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usememo",children:"#"}),"useMemo"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'const App = () => {\n  const title = useMemo(() => "dao", []);\n  return <div>{title}</div>;\n};\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"usecallback",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usecallback",children:"#"}),"useCallback"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'const App = () => {\n  const setTitle = useCallback(() => {\n    console.log(111);\n  }, []);\n  return (\n    <button\n      type="button"\n      onClick={setTitle}\n    >\n      +\n    </button>\n  );\n};\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"usememocache",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usememocache",children:"#"}),"useMemoCache"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h2,{id:"디버깅관련",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#디버깅관련",children:"#"}),"디버깅관련"]}),"\n",(0,s.jsxs)(n.h3,{id:"usedebugvalue",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usedebugvalue",children:"#"}),"useDebugValue"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h2,{id:"concurrent-동시성모드",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#concurrent-동시성모드",children:"#"}),"concurrent 동시성모드"]}),"\n",(0,s.jsxs)(n.h3,{id:"usetransition",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usetransition",children:"#"}),"useTransition"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'const App = () => {\n  const [value, setValue] = useState("");\n  const [isPending, startTransition] = useTransition();\n\n  const onChange = (e: ChangeEvent<HTMLInputElement>) => {\n    // 긴급처리건.\n    setValue(e.target.value);\n    startTransition(() => {\n      // 후순위 처리.\n      setContent(e.target.value);\n    });\n  };\n  return (\n    <>\n      <input\n        value={value}\n        onChange={onChange}\n      />\n      <div>{value.length}</div>\n    </>\n  );\n};\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"usedeferredvalue",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usedeferredvalue",children:"#"}),"useDeferredValue"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'const App = () => {\n  const [value, setValue] = useState("");\n  const lowValue = useDeferredValue(value.length);\n\n  const onChange = (e: ChangeEvent<HTMLInputElement>) => {\n    setValue(e.target.value);\n  };\n\n  return (\n    <>\n      <input\n        value={value}\n        onChange={onChange}\n      />\n      <div>{lowValue}</div>\n    </>\n  );\n};\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"usesyncexternalstoreusemutablesource",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usesyncexternalstoreusemutablesource",children:"#"}),"useSyncExternalStore(useMutableSource)"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'// todoStore.js\nlet nextId = 0;\nlet todos = [{ id: nextId++, text: "Todo #1" }];\nlet listeners = [];\n\nexport const todosStore = {\n  addTodo() {\n    todos = [...todos, { id: nextId++, text: "Todo #" + nextId }];\n    emitChange();\n  },\n  subscribe(listener) {\n    listeners = [...listeners, listener];\n    return () => {\n      listeners = listeners.filter((l) => l !== listener);\n    };\n  },\n  getSnapshot() {\n    return todos;\n  },\n};\n\nfunction emitChange() {\n  for (let listener of listeners) {\n    listener();\n  }\n}\n\n// App.jsx\nconst App = () => {\n  const todos = useSyncExternalStore(todosStore.subscribe, todosStore.getSnapshot);\n  return (\n    <>\n      <button onClick={todosStore.addTodo}>Add todo</button>\n      <hr />\n      <ul>\n        {todos.map((todo) => (\n          <li key={todo.id}>{todo.text}</li>\n        ))}\n      </ul>\n    </>\n  );\n};\n'})}),"\n",(0,s.jsxs)(n.h2,{id:"rscreact-server-component",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#rscreact-server-component",children:"#"}),"RSC(React Server Component)"]}),"\n",(0,s.jsxs)(n.h3,{id:"useid",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useid",children:"#"}),"useId"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx",children:'// 여러번 불러오는 컴포넌트일 경우 아래와 같이 필요함.\nconst App = () => {\n  const inputId = useId();\n\n  return (\n    <>\n      <input id={`${inputId}-firstName`} />\n      <input id={`${inputId}-lastName`} />\n    </>\n  );\n};\n// ssr일경우 생성한 id가 다른경우가 있음.\n// 1. 서버단 생성시 id="1" 생성\n// 2. <div id="1"></div>을 client에게 전달\n// 3. csr랭딩시 hydrate를 통해서 id="2" 로 처리.\n'})}),"\n",(0,s.jsxs)(n.h3,{id:"use",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#use",children:"#"}),"use"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h3,{id:"usecacherefresh",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#usecacherefresh",children:"#"}),"useCacheRefresh"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h3,{id:"useoptimistic",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useoptimistic",children:"#"}),"useOptimistic"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})}),"\n",(0,s.jsxs)(n.h3,{id:"useformstatusreact-dom",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#useformstatusreact-dom",children:"#"}),"useFormStatus(react-dom)"]}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-jsx"})})]})}function d(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,r.ah)(),e.components);return n?(0,s.jsx)(n,Object.assign({},e,{children:(0,s.jsx)(a,e)})):a(e)}let c=d;d.__RSPRESS_PAGE_META={},d.__RSPRESS_PAGE_META["note%2Freact%2Fhooks.md"]={toc:[{id:"usestate",text:"useState",depth:3},{id:"usereducer",text:"useReducer",depth:3},{id:"usecontext",text:"useContext",depth:3},{id:"effect관련",text:"effect관련",depth:2},{id:"useeffect",text:"useEffect",depth:3},{id:"uselayouteffect",text:"useLayoutEffect",depth:3},{id:"useinsertioneffect",text:"useInsertionEffect",depth:3},{id:"useeffectevent",text:"useEffectEvent",depth:3},{id:"ref관련",text:"ref관련",depth:2},{id:"useref",text:"useRef",depth:3},{id:"useimperativehandle",text:"useImperativeHandle",depth:3},{id:"성능관련",text:"성능관련",depth:2},{id:"usememo",text:"useMemo",depth:3},{id:"usecallback",text:"useCallback",depth:3},{id:"usememocache",text:"useMemoCache",depth:3},{id:"디버깅관련",text:"디버깅관련",depth:2},{id:"usedebugvalue",text:"useDebugValue",depth:3},{id:"concurrent-동시성모드",text:"concurrent 동시성모드",depth:2},{id:"usetransition",text:"useTransition",depth:3},{id:"usedeferredvalue",text:"useDeferredValue",depth:3},{id:"usesyncexternalstoreusemutablesource",text:"useSyncExternalStore(useMutableSource)",depth:3},{id:"rscreact-server-component",text:"RSC(React Server Component)",depth:2},{id:"useid",text:"useId",depth:3},{id:"use",text:"use",depth:3},{id:"usecacherefresh",text:"useCacheRefresh",depth:3},{id:"useoptimistic",text:"useOptimistic",depth:3},{id:"useformstatusreact-dom",text:"useFormStatus(react-dom)",depth:3}],title:"Hooks",headingTitle:"Hooks",frontmatter:{}}}}]);