"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["9709"],{5360:function(e,n,r){r.r(n),r.d(n,{default:()=>c});var t=r(5893),s=r(65);function a(e){let n=Object.assign({h1:"h1",a:"a",p:"p",pre:"pre",code:"code"},(0,s.ah)(),e.components);return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsxs)(n.h1,{id:"构建者模式builder",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#构建者模式builder",children:"#"}),"构建者模式(Builder)"]}),"\n",(0,t.jsx)(n.p,{children:"将对象构造与其表示分开"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:'class Starbucks {\n  constructor() {}\n  create(builder) {\n    builder.step1();\n    builder.step2();\n    builder.step3();\n    return builder.get();\n  }\n}\n\nclass Americano {\n  constructor() {\n    this.water = null;\n    this.coffee = null;\n  }\n  addCoffee() {\n    this.coffee = "20g";\n  }\n  addWater() {\n    this.water = "400ml";\n  }\n}\n\nclass AmericanoBuilder {\n  constructor() {\n    this.drink = null;\n  }\n  step1() {\n    this.drink = new Americano();\n  }\n  step2() {\n    this.drink.addCoffee();\n  }\n  step3() {\n    this.drink.addWater();\n  }\n  get() {\n    return this.drink;\n  }\n}\n\nexport { Starbucks, AmericanoBuilder };\n'})}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:"let starbucks = new Starbucks();\nlet americanoBuilder = new AmericanoBuilder();\nstarbucks.create(americanoBuilder);\n"})})]})}function i(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,s.ah)(),e.components);return n?(0,t.jsx)(n,Object.assign({},e,{children:(0,t.jsx)(a,e)})):a(e)}let c=i;i.__RSPRESS_PAGE_META={},i.__RSPRESS_PAGE_META["algorithm%2Fpattern%2F02.md"]={toc:[],title:"构建者模式(Builder)",headingTitle:"构建者模式(Builder)",frontmatter:{}}}}]);