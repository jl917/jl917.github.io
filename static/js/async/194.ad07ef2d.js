"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["194"],{94395:function(e,n,s){s.r(n);var t=s(85893),r=s(50065);function a(e){let n=Object.assign({h1:"h1",a:"a",h4:"h4",pre:"pre",code:"code",h3:"h3",p:"p"},(0,r.ah)(),e.components);return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsxs)(n.h1,{id:"babel",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#babel",children:"#"}),"babel"]}),"\n",(0,t.jsxs)(n.h4,{id:"sample-code",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#sample-code",children:"#"}),"sample code"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:"export default `function square(n) {\n  return n * n;\n}`;\n"})}),"\n",(0,t.jsxs)(n.h3,{id:"parser",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#parser",children:"#"}),"parser"]}),"\n",(0,t.jsx)(n.p,{children:"babel-parser(\u65E7 Babylon) \u662F Babel \u7684\u89E3\u6790\u5668\u3002\u6700\u521D\u662F \u4ECE Acorn \u9879\u76EE fork \u51FA\u6765\u7684\u3002Acorn \u975E\u5E38\u5FEB\uFF0C\u6613\u4E8E\u4F7F\u7528\uFF0C\u5E76\u4E14\u9488\u5BF9\u975E\u6807\u51C6\u7279\u6027(\u4EE5\u53CA\u90A3\u4E9B\u672A\u6765\u7684\u6807\u51C6\u7279\u6027) \u8BBE\u8BA1\u4E86\u4E00\u4E2A\u57FA\u4E8E\u63D2\u4EF6\u7684\u67B6\u6784\u3002"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-javascript",meta:"",children:'import { parse } from "@babel/parser";\nimport code from "./code";\n\nconst ast = parse(code);\nconsole.log(JSON.stringify(ast, null, 2));\n'})}),"\n",(0,t.jsxs)(n.h3,{id:"traverse",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#traverse",children:"#"}),"traverse"]}),"\n",(0,t.jsx)(n.p,{children:"Babel Traverse\uFF08\u904D\u5386\uFF09\u6A21\u5757\u7EF4\u62A4\u4E86\u6574\u68F5\u6811\u7684\u72B6\u6001\uFF0C\u5E76\u4E14\u8D1F\u8D23\u66FF\u6362\u3001\u79FB\u9664\u548C\u6DFB\u52A0\u8282\u70B9\u3002"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:'import { parse } from "@babel/parser";\nimport traverse from "@babel/traverse";\nimport code from "./code";\n\nconst ast = parse(code);\ntraverse(ast, {\n  enter(path) {\n    if (path.node.type === "Identifier" && path.node.name === "n") {\n      path.node.name = "x";\n    }\n  },\n});\n\nconsole.log(JSON.stringify(ast, null, 2));\n'})}),"\n",(0,t.jsxs)(n.h3,{id:"types",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#types",children:"#"}),"types"]}),"\n",(0,t.jsx)(n.p,{children:"Babel Types \u6A21\u5757\u662F\u4E00\u4E2A\u7528\u4E8E AST \u8282\u70B9\u7684 Lodash \u5F0F\u5DE5\u5177\u5E93\uFF08\u8BD1\u6CE8\uFF1ALodash \u662F\u4E00\u4E2A JavaScript \u51FD\u6570\u5DE5\u5177\u5E93\uFF0C\u63D0\u4F9B\u4E86\u57FA\u4E8E\u51FD\u6570\u5F0F\u7F16\u7A0B\u98CE\u683C\u7684\u4F17\u591A\u5DE5\u5177\u51FD\u6570\uFF09\uFF0C \u5B83\u5305\u542B\u4E86\u6784\u9020\u3001\u9A8C\u8BC1\u4EE5\u53CA\u53D8\u6362 AST \u8282\u70B9\u7684\u65B9\u6CD5\u3002 \u8BE5\u5DE5\u5177\u5E93\u5305\u542B\u8003\u8651\u5468\u5230\u7684\u5DE5\u5177\u65B9\u6CD5\uFF0C\u5BF9\u7F16\u5199\u5904\u7406 AST \u903B\u8F91\u975E\u5E38\u6709\u7528\u3002"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:'import { parse } from "@babel/parser";\nimport traverse from "@babel/traverse";\nimport * as t from "@babel/types";\nimport code from "./code";\n\nconst ast = parse(code);\ntraverse(ast, {\n  enter(path) {\n    if (t.isIdentifier(path.node, { name: "n" })) {\n      path.node.name = "x";\n    }\n  },\n});\nconsole.log(JSON.stringify(ast, null, 2));\n'})}),"\n",(0,t.jsxs)(n.h3,{id:"generator",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#generator",children:"#"}),"generator"]}),"\n",(0,t.jsx)(n.p,{children:"Babel Traverse\uFF08\u904D\u5386\uFF09\u6A21\u5757\u7EF4\u62A4\u4E86\u6574\u68F5\u6811\u7684\u72B6\u6001\uFF0C\u5E76\u4E14\u8D1F\u8D23\u66FF\u6362\u3001\u79FB\u9664\u548C\u6DFB\u52A0\u8282\u70B9\u3002"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:'import { parse } from "@babel/parser";\nimport traverse from "@babel/traverse";\nimport * as t from "@babel/types";\nimport generate from "@babel/generator";\nimport code from "./code";\n\nconst ast = parse(code);\ntraverse(ast, {\n  enter(path) {\n    if (t.isIdentifier(path.node, { name: "n" })) {\n      path.node.name = "x";\n    }\n  },\n});\nconsole.log(generate(ast).code);\n'})}),"\n",(0,t.jsxs)(n.h3,{id:"template",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#template",children:"#"}),"template"]}),"\n",(0,t.jsx)(n.p,{children:"\u5B83\u80FD\u8BA9\u4F60\u7F16\u5199\u5B57\u7B26\u4E32\u5F62\u5F0F\u4E14\u5E26\u6709\u5360\u4F4D\u7B26\u7684\u4EE3\u7801\u6765\u4EE3\u66FF\u624B\u52A8\u7F16\u7801\uFF0C \u5C24\u5176\u662F\u751F\u6210\u7684\u5927\u89C4\u6A21 AST \u7684\u65F6\u5019\u3002 \u5728\u8BA1\u7B97\u673A\u79D1\u5B66\u4E2D\uFF0C\u8FD9\u79CD\u80FD\u529B\u88AB\u79F0\u4E3A\u51C6\u5F15\u7528\uFF08quasiquotes\uFF09\u3002"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:'import template from "@babel/template";\nimport generate from "@babel/generator";\nimport * as t from "@babel/types";\n\nconst buildRequire = template(`\n  var IMPORT_NAME = require(SOURCE);\n`);\nconst ast = buildRequire({\n  IMPORT_NAME: t.identifier("myModule"),\n  SOURCE: t.stringLiteral("my-module"),\n});\nconsole.log(generate(ast).code);\n'})}),"\n",(0,t.jsxs)(n.h3,{id:"types-vistor",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#types-vistor",children:"#"}),"types-vistor"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",meta:"",children:"// types\nconst EXPRESSION_TYPES = [];\nconst BINARY_TYPES = [];\nconst SCOPABLE_TYPES = [];\nconst BLOCKPARENT_TYPES = [];\nconst BLOCK_TYPES = [];\nconst STATEMENT_TYPES = [];\nconst TERMINATORLESS_TYPES = [];\nconst COMPLETIONSTATEMENT_TYPES = [];\nconst CONDITIONAL_TYPES = [];\nconst LOOP_TYPES = [];\nconst WHILE_TYPES = [];\nconst EXPRESSIONWRAPPER_TYPES = [];\nconst FOR_TYPES = [];\nconst FORXSTATEMENT_TYPES = [];\nconst FUNCTION_TYPES = [];\nconst FUNCTIONPARENT_TYPES = [];\nconst PUREISH_TYPES = [];\nconst DECLARATION_TYPES = [];\nconst PATTERNLIKE_TYPES = [];\nconst LVAL_TYPES = [];\nconst TSENTITYNAME_TYPES = [];\nconst LITERAL_TYPES = [];\nconst IMMUTABLE_TYPES = [];\nconst USERWHITESPACABLE_TYPES = [];\nconst METHOD_TYPES = [];\nconst OBJECTMEMBER_TYPES = [];\nconst PROPERTY_TYPES = [];\nconst UNARYLIKE_TYPES = [];\nconst PATTERN_TYPES = [];\nconst CLASS_TYPES = [];\nconst MODULEDECLARATION_TYPES = [];\nconst EXPORTDECLARATION_TYPES = [];\nconst MODULESPECIFIER_TYPES = [];\nconst PRIVATE_TYPES = [];\nconst FLOW_TYPES = [];\nconst FLOWTYPE_TYPES = [];\nconst FLOWBASEANNOTATION_TYPES = [];\nconst FLOWDECLARATION_TYPES = [];\nconst FLOWPREDICATE_TYPES = [];\nconst ENUMBODY_TYPES = [];\nconst ENUMMEMBER_TYPES = [];\nconst JSX_TYPES = [];\nconst TSTYPEELEMENT_TYPES = [];\nconst TSTYPE_TYPES = [];\nconst TSBASETYPE_TYPES = [];\n\n// operator\nconst LOGICAL_OPERATORS = [];\nconst UPDATE_OPERATORS = [];\nconst BOOLEAN_NUMBER_BINARY_OPERATORS = [];\nconst EQUALITY_BINARY_OPERATORS = [];\nconst COMPARISON_BINARY_OPERATORS = [];\nconst BOOLEAN_BINARY_OPERATORS = [];\nconst NUMBER_BINARY_OPERATORS = [];\nconst BINARY_OPERATORS = [];\nconst ASSIGNMENT_OPERATORS = [];\nconst BOOLEAN_UNARY_OPERATORS = [];\nconst NUMBER_UNARY_OPERATORS = [];\nconst STRING_UNARY_OPERATORS = [];\nconst UNARY_OPERATORS = [];\n"})})]})}function o(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,r.ah)(),e.components);return n?(0,t.jsx)(n,Object.assign({},e,{children:(0,t.jsx)(a,e)})):a(e)}n.default=o,o.__RSPRESS_PAGE_META={},o.__RSPRESS_PAGE_META["tools%2Fplugins%2Fbabel.md"]={toc:[{id:"sample-code",text:"sample code",depth:4},{id:"parser",text:"parser",depth:3},{id:"traverse",text:"traverse",depth:3},{id:"types",text:"types",depth:3},{id:"generator",text:"generator",depth:3},{id:"template",text:"template",depth:3},{id:"types-vistor",text:"types-vistor",depth:3}],title:"babel",frontmatter:{}}}}]);