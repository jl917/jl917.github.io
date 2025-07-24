# eslint

1. code
2. parser(esprima, @babel/eslint-parser, @typescript-eslint/parser)
3. AST
4. plugin(report or fix)

### build option

```js
// build.js
require("esbuild")
  .build({
    entryPoints: ["./src/index.js"],
    bundle: true,
    minify: true,
    platform: "node",
    outfile: "./lib/index.js",
    watch: true,
  })
  .then(() => console.log("빌드완료"))
  .catch(() => process.exit(1));
```

```js
// ./src/index.js
// module.exports = {
//   rules: {......},
//   configs: {......},
// }
import maxParams from "./rules/max-params";

export const rules = {
  "max-params": maxParams,
};

export const configs = {
  recommended: {
    plugins: ["jl"],
    rules: {
      "jl/max-params": ["warn", { max: 3 }],
    },
  },
};

// ./src/rules/max-params.js
// https://astexplorer.net/
export default {
  meta: {
    docs: {
      description: "enforce a maximum number of parameters in function definitions",
      category: "Stylistic Issues", // https://cn.eslint.org/docs/rules/
      recommended: false,
    },
  },
  create: (context) => {
    // https://eslint.org/docs/developer-guide/working-with-rules#the-context-object
    // context: {
    //   report,
    //   id, // jl/max-params
    //   options, // [{max: 3}, ......]
    //   ......
    // }
    const fn = (node) => {
      if (node.params.length > 3) {
        // https://eslint.org/docs/developer-guide/working-with-rules
        context.report({
          node,
          message: "파라미터는 3개 이상 초과 할수 없습니다." + context.options[0].max,
        });
      }
    };
    // https://esprima.readthedocs.io/en/latest/syntax-tree-format.html#expressions-and-patterns
    return {
      FunctionDeclaration: fn,
      ArrowFunctionExpression: fn,
      FunctionExpression: fn,
    };
  },
};
```

### use plugin

```js
// .eslintrc.json
{
  "plugins": [
    "jl"
  ],
  "extends": [
    "plugin:jl/recommended"
  ],
  "rules": {
    "jl/max-params": "off", // off, warn, error, 0, 1,2
    "jl/max-params": ["warn", {"max": 3}],
  }
}
```
