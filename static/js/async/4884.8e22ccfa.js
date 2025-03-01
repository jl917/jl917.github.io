"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["4884"],{5308:function(e,n,t){t.r(n),t.d(n,{default:()=>c});var s=t(5893),r=t(65);function i(e){let n=Object.assign({h1:"h1",a:"a",p:"p",pre:"pre",code:"code"},(0,r.ah)(),e.components);return(0,s.jsxs)(s.Fragment,{children:[(0,s.jsxs)(n.h1,{id:"迭代器模式iterator",children:[(0,s.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#迭代器模式iterator",children:"#"}),"迭代器模式(Iterator)"]}),"\n",(0,s.jsx)(n.p,{children:"提供一种顺序访问聚合对象元素的方法，而不会暴露其基础表示。"}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-js",children:"class Iterator {\n  constructor(items) {\n    this.index = 0;\n    this.items = items;\n  }\n  first() {\n    this.index = 0;\n    return this.items[0];\n  }\n  next() {\n    this.index++;\n    return this.items[this.index];\n  }\n  hasNext() {\n    return this.items.length - 1 > this.index;\n  }\n  reset() {\n    this.index = 0;\n  }\n  each(callback) {\n    for (let i = 0; i < this.items.length; i++) {\n      callback(this.items[i]);\n    }\n  }\n}\n"})}),"\n",(0,s.jsx)(n.pre,{children:(0,s.jsx)(n.code,{className:"language-js",children:'const items = ["one", 2, "circle", true, "Applepie"];\nconst iter = new Iterator(items);\n\nconsole.log(iter.first());\nconsole.log(iter.next());\nconsole.log(iter.next());\nconsole.log(iter.next());\nconsole.log(iter.hasNext());\nconsole.log(iter.next());\n\niter.each(function (e) {\n  console.log(e);\n});\n'})})]})}function o(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,r.ah)(),e.components);return n?(0,s.jsx)(n,Object.assign({},e,{children:(0,s.jsx)(i,e)})):i(e)}let c=o;o.__RSPRESS_PAGE_META={},o.__RSPRESS_PAGE_META["algorithm%2Fpattern%2F16.md"]={toc:[],title:"迭代器模式(Iterator)",headingTitle:"迭代器模式(Iterator)",frontmatter:{}}}}]);