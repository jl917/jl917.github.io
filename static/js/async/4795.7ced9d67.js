"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["4795"],{1947:function(s,n,r){r.r(n),r.d(n,{default:()=>a});var e=r(5893),t=r(65);function i(s){let n=Object.assign({h1:"h1",a:"a",h3:"h3",pre:"pre",code:"code"},(0,t.ah)(),s.components);return(0,e.jsxs)(e.Fragment,{children:[(0,e.jsxs)(n.h1,{id:"swc",children:[(0,e.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#swc",children:"#"}),"swc"]}),"\n",(0,e.jsxs)(n.h3,{id:"visitor",children:[(0,e.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#visitor",children:"#"}),"Visitor"]}),"\n",(0,e.jsx)(n.pre,{children:(0,e.jsx)(n.code,{className:"language-tsx",children:'// https://swc.rs/docs/usage/core\nimport { CallExpression, parseSync, transformSync } from "@swc/core";\n// https://github.com/swc-project/swc/blob/main/node-swc/src/Visitor.ts\nimport { Visitor } from "@swc/core/Visitor";\n\ninterface ITransformResult {\n  code: string;\n  map?: string;\n}\nclass PluginName extends Visitor {\n  // run visitor\n  visitCallExpression(n: CallExpression) {\n    // any work\n    return super.visitCallExpression(n);\n  }\n}\n\nnew PluginName().visitProgram(program);\n\nconst transform = (src, id) => {\n  // default 값 지정 필요.\n  let result: ITransformResult = { code: src };\n\n  // 코드 변환\n  result = transformSync(src, {\n    plugin,\n    sourceMaps: true,\n    jsc: {\n      parser: {\n        syntax: "ecmascript",\n        jsx: true,\n      },\n    },\n  });\n  return result;\n};\n'})})]})}function c(){let s=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,t.ah)(),s.components);return n?(0,e.jsx)(n,Object.assign({},s,{children:(0,e.jsx)(i,s)})):i(s)}let a=c;c.__RSPRESS_PAGE_META={},c.__RSPRESS_PAGE_META["tools%2Fplugins%2Fswc.md"]={toc:[{id:"visitor",text:"Visitor",depth:3}],title:"swc",headingTitle:"swc",frontmatter:{}}}}]);