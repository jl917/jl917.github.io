"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["4189"],{7364:function(e,n,s){s.r(n),s.d(n,{default:()=>o});var t=s(5893),r=s(65);function c(e){let n=Object.assign({h1:"h1",a:"a",p:"p",pre:"pre",code:"code"},(0,r.ah)(),e.components);return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsxs)(n.h1,{id:"外观模式facade",children:[(0,t.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#外观模式facade",children:"#"}),"外观模式(Facade)"]}),"\n",(0,t.jsx)(n.p,{children:"为子系统中的一组接口提供统一接口。 Fa\xe7ade 定义了一个更高级别的接口，使子系统更易于使用。"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:'class s1 {\n  constructor() {}\n  get(name, score) {\n    return name == "julong" || score == 100;\n  }\n}\n\nclass s2 {\n  constructor() {}\n  get(score) {\n    return score >= 80;\n  }\n}\n\nclass s3 {\n  constructor() {}\n  get(score) {\n    return score >= 60;\n  }\n}\n\nclass Student {\n  constructor(name, score) {\n    this.name = name;\n    this.score = score;\n  }\n}\nclass CheckScore {\n  constructor(student) {\n    this.name = student.name;\n    this.score = student.score;\n    this.message = "";\n  }\n  result() {\n    if (new s1().get(this.name, this.score)) {\n      this.message = "全球旅游";\n    } else if (new s2().get(this.score)) {\n      this.message = "挨打50大板";\n    } else if (new s3().get(this.score)) {\n      this.message = "强制移民去印度";\n    } else {\n      this.message = "死刑";\n    }\n    return this;\n  }\n}\n\nexport { Student, CheckScore };\n'})}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:'let s01 = new Student("julong", 50);\nlet s01Check = new CheckScore(s01);\ns01Check.result();\n'})})]})}function a(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,r.ah)(),e.components);return n?(0,t.jsx)(n,Object.assign({},e,{children:(0,t.jsx)(c,e)})):c(e)}let o=a;a.__RSPRESS_PAGE_META={},a.__RSPRESS_PAGE_META["algorithm%2Fpattern%2F10.md"]={toc:[],title:"外观模式(Facade)",headingTitle:"外观模式(Facade)",frontmatter:{}}}}]);