"use strict";(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["9925"],{92082:function(e,n,t){t.r(n);var a=t(85893),o=t(50065);function s(e){let n=Object.assign({h1:"h1",a:"a",h2:"h2",pre:"pre",code:"code",h3:"h3"},(0,o.ah)(),e.components);return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsxs)(n.h1,{id:"web-storage",children:[(0,a.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#web-storage",children:"#"}),"Web Storage"]}),"\n",(0,a.jsxs)(n.h2,{id:"localstorage-sessionstorage",children:[(0,a.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#localstorage-sessionstorage",children:"#"}),"localStorage, sessionStorage"]}),"\n",(0,a.jsx)(n.pre,{children:(0,a.jsx)(n.code,{className:"language-js",meta:"",children:'localStorage.setItem("myCat", "Tom");\nlocalStorage.getItem("myCat");\nlocalStorage.removeItem("myCat");\nlocalStorage.clear();\n'})}),"\n",(0,a.jsxs)(n.h2,{id:"setitem-\uAC12-\uBAA8\uB2C8\uD130\uB9C1",children:[(0,a.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#setitem-\uAC12-\uBAA8\uB2C8\uD130\uB9C1",children:"#"}),"setItem \uAC12 \uBAA8\uB2C8\uD130\uB9C1"]}),"\n",(0,a.jsx)(n.pre,{children:(0,a.jsx)(n.code,{className:"language-js",meta:"",children:'const orignalSetItem = localStorage.setItem;\n\nlocalStorage.setItem = function (key, newValue) {\n  let setItemEvent = new Event("setItemEvent");\n  setItemEvent.value = localStorage.getItem(key); // \uD544\uC694\uC5D0 \uB530\uB77C \uC0AC\uC6A9.\n  setItemEvent.newValue = newValue;\n  setItemEvent.key = key;\n  window.dispatchEvent(setItemEvent);\n  orignalSetItem.apply(this, arguments);\n};\n\nwindow.addEventListener("setItemEvent", function (e) {\n  console.log(e);\n});\n'})}),"\n",(0,a.jsxs)(n.h3,{id:"cookie",children:[(0,a.jsx)(n.a,{className:"header-anchor","aria-hidden":"true",href:"#cookie",children:"#"}),"cookie"]}),"\n",(0,a.jsx)(n.pre,{children:(0,a.jsx)(n.code,{className:"language-js",meta:"",children:'// \u8BFB\u53D6Cookie\ndocument.cookie;\n\n// \u57FA\u672C\ndocument.cookie = "name=Raymond";\n// \u52A8\u6001\u4F7F\u7528\ndocument.cookie = "name=" + encodeURIComponent(name);\n// \u521B\u5EFA2\u4E2Acookie\ndocument.cookie = "name=Raymond";\ndocument.cookie = "age=43";\n// \u8BBE\u7F6E\u8FC7\u671F\ndocument.cookie = "name=Raymond; expires=Fri, 31 Dec 9999 23:59:59 GMT";\n// \u8BBE\u7F6E\u5B50\u57DF\u540D\u8BBF\u95EE\ndocument.cookie = "name=Raymond; domail=app.guryong.cc";\n\n// \u5220\u9664Cookie \u53EA\u9700\u628A\u65F6\u95F4\u8BBE\u7F6E\u4E3A\u8FC7\u53BB\u7684\u65F6\u95F4\ndocument.cookie = "name=Raymond; expires=Thu, 01 Jan 1970 00:00:00 GMT";\n'})})]})}function c(){let e=arguments.length>0&&void 0!==arguments[0]?arguments[0]:{},{wrapper:n}=Object.assign({},(0,o.ah)(),e.components);return n?(0,a.jsx)(n,Object.assign({},e,{children:(0,a.jsx)(s,e)})):s(e)}n.default=c,c.__RSPRESS_PAGE_META={},c.__RSPRESS_PAGE_META["web%2Fhtml%2Fstorage.md"]={toc:[{id:"localstorage-sessionstorage",text:"localStorage, sessionStorage",depth:2},{id:"setitem-\uAC12-\uBAA8\uB2C8\uD130\uB9C1",text:"setItem \uAC12 \uBAA8\uB2C8\uD130\uB9C1",depth:2},{id:"cookie",text:"cookie",depth:3}],title:"Web Storage",frontmatter:{}}}}]);