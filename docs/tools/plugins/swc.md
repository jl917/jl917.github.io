# swc

### Visitor

```tsx
// https://swc.rs/docs/usage/core
import { CallExpression, parseSync, transformSync } from "@swc/core";
// https://github.com/swc-project/swc/blob/main/node-swc/src/Visitor.ts
import { Visitor } from "@swc/core/Visitor";

interface ITransformResult {
  code: string;
  map?: string;
}
class PluginName extends Visitor {
  // run visitor
  visitCallExpression(n: CallExpression) {
    // any work
    return super.visitCallExpression(n);
  }
}

new PluginName().visitProgram(program);

const transform = (src, id) => {
  // default 값 지정 필요.
  let result: ITransformResult = { code: src };

  // 코드 변환
  result = transformSync(src, {
    plugin,
    sourceMaps: true,
    jsc: {
      parser: {
        syntax: "ecmascript",
        jsx: true,
      },
    },
  });
  return result;
};
```
