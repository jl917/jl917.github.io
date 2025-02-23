# Jest

### Install

```sh
npm i --save-dev jest
# 如果你在代码中使用了新的语法特性，而当前 Node 版本不支持，则需要使用 Babel 进行转义。
npm i --save-dev babel-jest @babel/core @babel/preset-env
npm i --save-dev identity-obj-proxy
npm i --save-dev react-test-renderer
npm i --save-dev enzyme enzyme-adapter-react-16 enzyme-to-json
npm i --save-dev @testing-library/react
```

### .babelrc.js

```js
const config = {
  presets: ["@babel/preset-env", "@babel/preset-react"],
  plugins: [],
};
if (process.env.NODE_ENV === "production") {
  config.presets.push([
    "minify",
    {
      removeConsole: true,
    },
  ]);
}
module.exports = config;
```

### jest.config.js

```js
module.exports = {
  testEnvironment: "node",
  transform: {
    "^.+\\.jsx?$": "babel-jest",
  },
  moduleNameMapper: {
    "\\.(jpg|jpeg|png|gif|eot|otf|webp|svg|ttf|woff|woff2|mp4|webm|wav|mp3|m4a|aac|oga)$": "./mocks/fileMock.js",
    "\\.(css|less|styl)$": "identity-obj-proxy",
  },
  snapshotSerializers: ["enzyme-to-json/serializer"],
  setupFiles: ["./setupTest.js"],
};
```

VScode - https://marketplace.visualstudio.com/items?itemName=Orta.vscode-jest

ts-jest - https://github.com/kulshekhar/ts-jest

### mocks/fileMock.js

```json
module.exports = '';
```

### setupTest.js

```js
import { configure } from "enzyme";
import Adapter from "enzyme-adapter-react-16";
configure({ adapter: new Adapter() });
```

### 基本用法

```js
import fn from "./someFunction";
describe("测试描述", () => {
  test("dao", () => {
    expect(fn("dao")).toEqual("daodao");
  });
  test("lang", () => {
    expect(fn("lang")).toEqual("langdao");
  });
});

// 异步
// .resolves
test("resolves to lemon", () => {
  // make sure to add a return statement
  return expect(Promise.resolve("lemon")).resolves.toBe("lemon");
});
// .rejects
test("rejects to octopus", () => {
  // make sure to add a return statement
  return expect(Promise.reject(new Error("octopus"))).rejects.toThrow("octopus");
});
```

### 比较

`toBe` 使用 [Object.is](https://developer.mozilla.org/zh-CN/docs/Web/JavaScript/Reference/Global_Objects/Object/is) 判断是否严格相等。

`toEqual` 递归检查对象或数组的每个字段。

`toBeNull` 只匹配 `null`。

`toBeUndefined` 只匹配 `undefined`。

`toBeDefined` 只匹配非 `undefined`。

`toBeTruthy` 只匹配真。

`toBeFalsy` 只匹配假。

`toBeGreaterThan` 实际值大于期望。

`toBeGreaterThanOrEqual` 实际值大于或等于期望值

`toBeLessThan` 实际值小于期望值。

`toBeLessThanOrEqual` 实际值小于或等于期望值。

`toBeCloseTo` 比较浮点数的值，避免误差。

`toMatch` 正则匹配。

`toContain` 判断数组中是否包含指定项。

`.toHaveProperty(keyPath, value)` 判断对象中是否包含指定属性。

`toThrow` 判断是否抛出指定的异常。

`toBeInstanceOf` 判断对象是否是某个类的实例，底层使用 `instanceof`。

### extends sample

```js
const expect = require("expect");

const toMatchAllText = (props, textList) => {
  let isMatchText = true;
  let noMatchtextList = [];
  for (const text of textList) {
    try {
      props.getByText(text);
    } catch {
      isMatchText = false;
      noMatchtextList.push(text);
    }
  }
  if (isMatchText) {
    return {
      message: () => "success",
      pass: true,
    };
  }
  return {
    message: () => `not match some text "${noMatchtextList.join(", ")}"`,
    pass: false,
  };
};

expect.extend({ toMatchAllText: toMatchAllText });

// expect(props.getByTestId('01').innerHTML).toMatchAllText('헤딩 타이틀');
```
