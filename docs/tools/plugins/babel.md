# babel

#### sample code

```js
export default `function square(n) {
  return n * n;
}`;
```

### parser

babel-parser(旧 Babylon) 是 Babel 的解析器。最初是 从 Acorn 项目 fork 出来的。Acorn 非常快，易于使用，并且针对非标准特性(以及那些未来的标准特性) 设计了一个基于插件的架构。

```javascript
import { parse } from "@babel/parser";
import code from "./code";

const ast = parse(code);
console.log(JSON.stringify(ast, null, 2));
```

### traverse

Babel Traverse（遍历）模块维护了整棵树的状态，并且负责替换、移除和添加节点。

```js
import { parse } from "@babel/parser";
import traverse from "@babel/traverse";
import code from "./code";

const ast = parse(code);
traverse(ast, {
  enter(path) {
    if (path.node.type === "Identifier" && path.node.name === "n") {
      path.node.name = "x";
    }
  },
});

console.log(JSON.stringify(ast, null, 2));
```

### types

Babel Types 模块是一个用于 AST 节点的 Lodash 式工具库（译注：Lodash 是一个 JavaScript 函数工具库，提供了基于函数式编程风格的众多工具函数）， 它包含了构造、验证以及变换 AST 节点的方法。 该工具库包含考虑周到的工具方法，对编写处理 AST 逻辑非常有用。

```js
import { parse } from "@babel/parser";
import traverse from "@babel/traverse";
import * as t from "@babel/types";
import code from "./code";

const ast = parse(code);
traverse(ast, {
  enter(path) {
    if (t.isIdentifier(path.node, { name: "n" })) {
      path.node.name = "x";
    }
  },
});
console.log(JSON.stringify(ast, null, 2));
```

### generator

Babel Traverse（遍历）模块维护了整棵树的状态，并且负责替换、移除和添加节点。

```js
import { parse } from "@babel/parser";
import traverse from "@babel/traverse";
import * as t from "@babel/types";
import generate from "@babel/generator";
import code from "./code";

const ast = parse(code);
traverse(ast, {
  enter(path) {
    if (t.isIdentifier(path.node, { name: "n" })) {
      path.node.name = "x";
    }
  },
});
console.log(generate(ast).code);
```

### template

它能让你编写字符串形式且带有占位符的代码来代替手动编码， 尤其是生成的大规模 AST 的时候。 在计算机科学中，这种能力被称为准引用（quasiquotes）。

```js
import template from "@babel/template";
import generate from "@babel/generator";
import * as t from "@babel/types";

const buildRequire = template(`
  var IMPORT_NAME = require(SOURCE);
`);
const ast = buildRequire({
  IMPORT_NAME: t.identifier("myModule"),
  SOURCE: t.stringLiteral("my-module"),
});
console.log(generate(ast).code);
```

### types-vistor

```js
// types
const EXPRESSION_TYPES = [];
const BINARY_TYPES = [];
const SCOPABLE_TYPES = [];
const BLOCKPARENT_TYPES = [];
const BLOCK_TYPES = [];
const STATEMENT_TYPES = [];
const TERMINATORLESS_TYPES = [];
const COMPLETIONSTATEMENT_TYPES = [];
const CONDITIONAL_TYPES = [];
const LOOP_TYPES = [];
const WHILE_TYPES = [];
const EXPRESSIONWRAPPER_TYPES = [];
const FOR_TYPES = [];
const FORXSTATEMENT_TYPES = [];
const FUNCTION_TYPES = [];
const FUNCTIONPARENT_TYPES = [];
const PUREISH_TYPES = [];
const DECLARATION_TYPES = [];
const PATTERNLIKE_TYPES = [];
const LVAL_TYPES = [];
const TSENTITYNAME_TYPES = [];
const LITERAL_TYPES = [];
const IMMUTABLE_TYPES = [];
const USERWHITESPACABLE_TYPES = [];
const METHOD_TYPES = [];
const OBJECTMEMBER_TYPES = [];
const PROPERTY_TYPES = [];
const UNARYLIKE_TYPES = [];
const PATTERN_TYPES = [];
const CLASS_TYPES = [];
const MODULEDECLARATION_TYPES = [];
const EXPORTDECLARATION_TYPES = [];
const MODULESPECIFIER_TYPES = [];
const PRIVATE_TYPES = [];
const FLOW_TYPES = [];
const FLOWTYPE_TYPES = [];
const FLOWBASEANNOTATION_TYPES = [];
const FLOWDECLARATION_TYPES = [];
const FLOWPREDICATE_TYPES = [];
const ENUMBODY_TYPES = [];
const ENUMMEMBER_TYPES = [];
const JSX_TYPES = [];
const TSTYPEELEMENT_TYPES = [];
const TSTYPE_TYPES = [];
const TSBASETYPE_TYPES = [];

// operator
const LOGICAL_OPERATORS = [];
const UPDATE_OPERATORS = [];
const BOOLEAN_NUMBER_BINARY_OPERATORS = [];
const EQUALITY_BINARY_OPERATORS = [];
const COMPARISON_BINARY_OPERATORS = [];
const BOOLEAN_BINARY_OPERATORS = [];
const NUMBER_BINARY_OPERATORS = [];
const BINARY_OPERATORS = [];
const ASSIGNMENT_OPERATORS = [];
const BOOLEAN_UNARY_OPERATORS = [];
const NUMBER_UNARY_OPERATORS = [];
const STRING_UNARY_OPERATORS = [];
const UNARY_OPERATORS = [];
```
