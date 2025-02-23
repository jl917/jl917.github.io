# React Test Sample

### basic

```jsx
// Button.jsx
import React, { useState } from "react";
import s from "./style.styl";

const Button = ({ text = "버튼" }) => {
  const [isActive, setActive] = useState(false);
  return (
    <button
      type="button"
      className={isActive ? s.active : ""}
      onClick={() => setActive(!isActive)}
    >
      {text}
    </button>
  );
};

export default Button;

// Button.test.js
import React from "react";
import { shallow } from "enzyme";
import Button from "./";

describe("测试组件", () => {
  const component = shallow(<Button text="확인" />);

  test("default componet", () => {
    expect(component).toMatchSnapshot();
    expect(component.props().className).toEqual("");
  });
  test("component clicked", () => {
    expect(component).toMatchSnapshot();
    component.find("button").at(0).simulate("click");
    expect(component.props().className).toEqual("active");
  });
});
```

### redux

```jsx
// page1/redux.jsx
export const NEXT_COUNT = "NEXT_COUNT";
export const PREV_COUNT = "PREV_COUNT";

export const setCount = (type) => ({
  type: type === "next" ? NEXT_COUNT : PREV_COUNT,
});

export const countReducer = (state = 0, action) => {
  if (action.type === NEXT_COUNT) {
    return state + 1;
  }
  if (action.type === PREV_COUNT) {
    return state - 1;
  }
  return state;
};

// page1/redux.test.js
import { NEXT_COUNT, PREV_COUNT, setCount, countReducer } from "./redux";

describe("PAGE1 测试redux", () => {
  test("action creator test", () => {
    expect(setCount("next")).toEqual({ type: NEXT_COUNT });
    expect(setCount("prev")).toEqual({ type: PREV_COUNT });
  });

  test("reducer test", () => {
    expect(countReducer(0, { type: NEXT_COUNT })).toEqual(1);
    expect(countReducer(10, { type: NEXT_COUNT })).toEqual(11);
    expect(countReducer(-10, { type: NEXT_COUNT })).toEqual(-9);

    expect(countReducer(0, { type: PREV_COUNT })).toEqual(-1);
    expect(countReducer(10, { type: PREV_COUNT })).toEqual(9);
    expect(countReducer(-10, { type: PREV_COUNT })).toEqual(-11);
  });
});
```
