/*! For license information please see lib-router.019ef239.js.LICENSE.txt */
(self.webpackChunkrspress_doc_template=self.webpackChunkrspress_doc_template||[]).push([["2118"],{12599:function(e,t,n){"use strict";var r,a,o,i;function l(){return(l=Object.assign?Object.assign.bind():function(e){for(var t=1;t<arguments.length;t++){var n=arguments[t];for(var r in n)Object.prototype.hasOwnProperty.call(n,r)&&(e[r]=n[r])}return e}).apply(this,arguments)}n.d(t,{Ep:function(){return d},J0:function(){return c},LX:function(){return w},RQ:function(){return R},WK:function(){return W},X3:function(){return E},Zn:function(){return b},aU:function(){return r},cP:function(){return m},cm:function(){return S},fp:function(){return v},lX:function(){return s},pC:function(){return C}}),(o=r||(r={})).Pop="POP",o.Push="PUSH",o.Replace="REPLACE";let u="popstate";function s(e){return void 0===e&&(e={}),function(e,t,n,a){void 0===a&&(a={});let{window:o=document.defaultView,v5Compat:i=!1}=a,s=o.history,h=r.Pop,m=null,v=g();function g(){return(s.state||{idx:null}).idx}function y(){h=r.Pop;let e=g(),t=null==e?null:e-v;v=e,m&&m({action:h,location:b.location,delta:t})}null==v&&(v=0,s.replaceState(l({},s.state,{idx:v}),""));function w(e){let t="null"!==o.location.origin?o.location.origin:o.location.href,n="string"==typeof e?e:d(e);return c(t,"No window.location.(origin|href) available to create URL for href: "+(n=n.replace(/ $/,"%20"))),new URL(n,t)}let b={get action(){return h},get location(){return e(o,s)},listen(e){if(m)throw Error("A history only accepts one active listener");return o.addEventListener(u,y),m=e,()=>{o.removeEventListener(u,y),m=null}},createHref:e=>t(o,e),createURL:w,encodeLocation(e){let t=w(e);return{pathname:t.pathname,search:t.search,hash:t.hash}},push:function(e,t){h=r.Push;let a=f(b.location,e,t);n&&n(a,e);let l=p(a,v=g()+1),u=b.createHref(a);try{s.pushState(l,"",u)}catch(e){if(e instanceof DOMException&&"DataCloneError"===e.name)throw e;o.location.assign(u)}i&&m&&m({action:h,location:b.location,delta:1})},replace:function(e,t){h=r.Replace;let a=f(b.location,e,t);n&&n(a,e);let o=p(a,v=g()),l=b.createHref(a);s.replaceState(o,"",l),i&&m&&m({action:h,location:b.location,delta:0})},go:e=>s.go(e)};return b}(function(e,t){let{pathname:n,search:r,hash:a}=e.location;return f("",{pathname:n,search:r,hash:a},t.state&&t.state.usr||null,t.state&&t.state.key||"default")},function(e,t){return"string"==typeof t?t:d(t)},null,e)}function c(e,t){if(!1===e||null==e)throw Error(t)}function h(e,t){if(!e){"undefined"!=typeof console&&console.warn(t);try{throw Error(t)}catch(e){}}}function p(e,t){return{usr:e.state,key:e.key,idx:t}}function f(e,t,n,r){return void 0===n&&(n=null),l({pathname:"string"==typeof e?e:e.pathname,search:"",hash:""},"string"==typeof t?m(t):t,{state:n,key:t&&t.key||r||Math.random().toString(36).substr(2,8)})}function d(e){let{pathname:t="/",search:n="",hash:r=""}=e;return n&&"?"!==n&&(t+="?"===n.charAt(0)?n:"?"+n),r&&"#"!==r&&(t+="#"===r.charAt(0)?r:"#"+r),t}function m(e){let t={};if(e){let n=e.indexOf("#");n>=0&&(t.hash=e.substr(n),e=e.substr(0,n));let r=e.indexOf("?");r>=0&&(t.search=e.substr(r),e=e.substr(0,r)),e&&(t.pathname=e)}return t}function v(e,t,n){void 0===n&&(n="/");let r=b(("string"==typeof t?m(t):t).pathname||"/",n);if(null==r)return null;let a=function e(t,n,r,a){void 0===n&&(n=[]),void 0===r&&(r=[]),void 0===a&&(a="");let o=(t,o,i)=>{let l={relativePath:void 0===i?t.path||"":i,caseSensitive:!0===t.caseSensitive,childrenIndex:o,route:t};l.relativePath.startsWith("/")&&(c(l.relativePath.startsWith(a),'Absolute route path "'+l.relativePath+'" nested under path '+('"'+a)+'" is not valid. An absolute child route path must start with the combined path of all its parent routes.'),l.relativePath=l.relativePath.slice(a.length));let u=R([a,l.relativePath]),s=r.concat(l);t.children&&t.children.length>0&&(c(!0!==t.index,'Index routes must not have child routes. Please remove all child routes from route path "'+u+'".'),e(t.children,n,s,u)),(null!=t.path||t.index)&&n.push({path:u,score:function(e,t){let n=e.split("/"),r=n.length;return n.some(y)&&(r+=-2),t&&(r+=2),n.filter(e=>!y(e)).reduce((e,t)=>e+(g.test(t)?3:""===t?1:10),r)}(u,t.index),routesMeta:s})};return t.forEach((e,t)=>{var n;if(""!==e.path&&null!=(n=e.path)&&n.includes("?"))for(let n of function e(t){let n=t.split("/");if(0===n.length)return[];let[r,...a]=n,o=r.endsWith("?"),i=r.replace(/\?$/,"");if(0===a.length)return o?[i,""]:[i];let l=e(a.join("/")),u=[];return u.push(...l.map(e=>""===e?i:[i,e].join("/"))),o&&u.push(...l),u.map(e=>t.startsWith("/")&&""===e?"/":e)}(e.path))o(e,t,n);else o(e,t)}),n}(e);(function(e){e.sort((e,t)=>e.score!==t.score?t.score-e.score:function(e,t){return e.length===t.length&&e.slice(0,-1).every((e,n)=>e===t[n])?e[e.length-1]-t[t.length-1]:0}(e.routesMeta.map(e=>e.childrenIndex),t.routesMeta.map(e=>e.childrenIndex)))})(a);let o=null;for(let e=0;null==o&&e<a.length;++e){let t=function(e){try{return e.split("/").map(e=>decodeURIComponent(e).replace(/\//g,"%2F")).join("/")}catch(t){return h(!1,'The URL path "'+e+'" could not be decoded because it is is a malformed URL segment. This is probably due to a bad percent '+("encoding ("+t)+")."),e}}(r);o=function(e,t){let{routesMeta:n}=e,r={},a="/",o=[];for(let e=0;e<n.length;++e){let i=n[e],l=e===n.length-1,u="/"===a?t:t.slice(a.length)||"/",s=w({path:i.relativePath,caseSensitive:i.caseSensitive,end:l},u);if(!s)return null;Object.assign(r,s.params);let c=i.route;o.push({params:r,pathname:R([a,s.pathname]),pathnameBase:U(R([a,s.pathnameBase])),route:c}),"/"!==s.pathnameBase&&(a=R([a,s.pathnameBase]))}return o}(a[e],t)}return o}(i=a||(a={})).data="data",i.deferred="deferred",i.redirect="redirect",i.error="error";let g=/^:[\w-]+$/,y=e=>"*"===e;function w(e,t){"string"==typeof e&&(e={path:e,caseSensitive:!1,end:!0});let[n,r]=function(e,t,n){void 0===t&&(t=!1),void 0===n&&(n=!0),h("*"===e||!e.endsWith("*")||e.endsWith("/*"),'Route path "'+e+'" will be treated as if it were '+('"'+e.replace(/\*$/,"/*"))+'" because the `*` character must always follow a `/` in the pattern. To get rid of this warning, '+('please change the route path to "'+e.replace(/\*$/,"/*"))+'".');let r=[],a="^"+e.replace(/\/*\*?$/,"").replace(/^\/*/,"/").replace(/[\\.*+^${}|()[\]]/g,"\\$&").replace(/\/:([\w-]+)(\?)?/g,(e,t,n)=>(r.push({paramName:t,isOptional:null!=n}),n?"/?([^\\/]+)?":"/([^\\/]+)"));return e.endsWith("*")?(r.push({paramName:"*"}),a+="*"===e||"/*"===e?"(.*)$":"(?:\\/(.+)|\\/*)$"):n?a+="\\/*$":""!==e&&"/"!==e&&(a+="(?:(?=\\/|$))"),[new RegExp(a,t?void 0:"i"),r]}(e.path,e.caseSensitive,e.end),a=t.match(n);if(!a)return null;let o=a[0],i=o.replace(/(.)\/+$/,"$1"),l=a.slice(1);return{params:r.reduce((e,t,n)=>{let{paramName:r,isOptional:a}=t;if("*"===r){let e=l[n]||"";i=o.slice(0,o.length-e.length).replace(/(.)\/+$/,"$1")}let u=l[n];return a&&!u?e[r]=void 0:e[r]=(u||"").replace(/%2F/g,"/"),e},{}),pathname:o,pathnameBase:i,pattern:e}}function b(e,t){if("/"===t)return e;if(!e.toLowerCase().startsWith(t.toLowerCase()))return null;let n=t.endsWith("/")?t.length-1:t.length,r=e.charAt(n);return r&&"/"!==r?null:e.slice(n)||"/"}function x(e,t,n,r){return"Cannot include a '"+e+"' character in a manually specified "+("`to."+t+"` field ["+JSON.stringify(r))+"].  Please separate it out to the "+("`to."+n)+'` field. Alternatively you may provide the full path as a string in <Link to="..."> and the router will parse it for you.'}function S(e,t){let n=e.filter((e,t)=>0===t||e.route.path&&e.route.path.length>0);return t?n.map((t,n)=>n===e.length-1?t.pathname:t.pathnameBase):n.map(e=>e.pathnameBase)}function C(e,t,n,r){let a,o;void 0===r&&(r=!1),"string"==typeof e?a=m(e):(c(!(a=l({},e)).pathname||!a.pathname.includes("?"),x("?","pathname","search",a)),c(!a.pathname||!a.pathname.includes("#"),x("#","pathname","hash",a)),c(!a.search||!a.search.includes("#"),x("#","search","hash",a)));let i=""===e||""===a.pathname,u=i?"/":a.pathname;if(null==u)o=n;else{let e=t.length-1;if(!r&&u.startsWith("..")){let t=u.split("/");for(;".."===t[0];)t.shift(),e-=1;a.pathname=t.join("/")}o=e>=0?t[e]:"/"}let s=function(e,t){void 0===t&&(t="/");let{pathname:n,search:r="",hash:a=""}="string"==typeof e?m(e):e;return{pathname:n?n.startsWith("/")?n:function(e,t){let n=t.replace(/\/+$/,"").split("/");return e.split("/").forEach(e=>{".."===e?n.length>1&&n.pop():"."!==e&&n.push(e)}),n.length>1?n.join("/"):"/"}(n,t):t,search:P(r),hash:O(a)}}(a,o),h=u&&"/"!==u&&u.endsWith("/"),p=(i||"."===u)&&n.endsWith("/");return!s.pathname.endsWith("/")&&(h||p)&&(s.pathname+="/"),s}let R=e=>e.join("/").replace(/\/\/+/g,"/"),U=e=>e.replace(/\/+$/,"").replace(/^\/*/,"/"),P=e=>e&&"?"!==e?e.startsWith("?")?e:"?"+e:"",O=e=>e&&"#"!==e?e.startsWith("#")?e:"#"+e:"";class E extends Error{}function W(e){return null!=e&&"number"==typeof e.status&&"string"==typeof e.statusText&&"boolean"==typeof e.internal&&"data"in e}Symbol("deferred")},79655:function(e,t,n){"use strict";n.d(t,{VK:function(){return S}});var r,a,o,i,l,u,s=n("67294"),c=n("73935"),h=n("89250"),p=n("12599");function f(){return(f=Object.assign?Object.assign.bind():function(e){for(var t=1;t<arguments.length;t++){var n=arguments[t];for(var r in n)Object.prototype.hasOwnProperty.call(n,r)&&(e[r]=n[r])}return e}).apply(this,arguments)}let d="application/x-www-form-urlencoded";function m(e){return null!=e&&"string"==typeof e.tagName}let v=null,g=new Set(["application/x-www-form-urlencoded","multipart/form-data","text/plain"]);function y(e){return null==e||g.has(e)?e:null}let w=["onClick","relative","reloadDocument","replace","state","target","to","preventScrollReset","unstable_viewTransition"];try{window.__reactRouterVersion="6"}catch(e){}let b=s.createContext({isTransitioning:!1}),x=(o||(o=n.t(s,2))).startTransition;(i||(i=n.t(c,2))).flushSync;function S(e){let{basename:t,children:n,future:r,window:a}=e,o=s.useRef();null==o.current&&(o.current=(0,p.lX)({window:a,v5Compat:!0}));let i=o.current,[l,u]=s.useState({action:i.action,location:i.location}),{v7_startTransition:c}=r||{},f=s.useCallback(e=>{c&&x?x(()=>u(e)):u(e)},[u,c]);return s.useLayoutEffect(()=>i.listen(f),[i,f]),s.createElement(h.F0,{basename:t,children:n,location:l.location,navigationType:l.action,navigator:i,future:r})}(o||(o=n.t(s,2))).useId;let C="undefined"!=typeof window&&void 0!==window.document&&void 0!==window.document.createElement,R=/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i;function U(e){let t=s.useContext(h.w3);return t||(0,p.J0)(!1),t}(r=l||(l={})).UseScrollRestoration="useScrollRestoration",r.UseSubmit="useSubmit",r.UseSubmitFetcher="useSubmitFetcher",r.UseFetcher="useFetcher",r.useViewTransitionState="useViewTransitionState",(a=u||(u={})).UseFetcher="useFetcher",a.UseFetchers="useFetchers",a.UseScrollRestoration="useScrollRestoration";let P=0,O=()=>"__"+String(++P)+"__"},89250:function(e,t,n){"use strict";n.d(t,{F0:function(){return P},FR:function(){return h},TH:function(){return y},Us:function(){return p},WU:function(){return x},Yi:function(){return U},oQ:function(){return v},pW:function(){return d},s0:function(){return b},w3:function(){return c}});var r,a,o,i,l=n("67294"),u=n("12599");function s(){return(s=Object.assign?Object.assign.bind():function(e){for(var t=1;t<arguments.length;t++){var n=arguments[t];for(var r in n)Object.prototype.hasOwnProperty.call(n,r)&&(e[r]=n[r])}return e}).apply(this,arguments)}let c=l.createContext(null),h=l.createContext(null),p=l.createContext(null),f=l.createContext(null),d=l.createContext({outlet:null,matches:[],isDataRoute:!1}),m=l.createContext(null);function v(e,t){let{relative:n}=void 0===t?{}:t;g()||(0,u.J0)(!1);let{basename:r,navigator:a}=l.useContext(p),{hash:o,pathname:i,search:s}=x(e,{relative:n}),c=i;return"/"!==r&&(c="/"===i?r:(0,u.RQ)([r,i])),a.createHref({pathname:c,search:s,hash:o})}function g(){return null!=l.useContext(f)}function y(){return g()||(0,u.J0)(!1),l.useContext(f).location}function w(e){!l.useContext(p).static&&l.useLayoutEffect(e)}function b(){let{isDataRoute:e}=l.useContext(d);return e?function(){var e;let t;let{router:n}=(e=S.UseNavigateStable,(t=l.useContext(c))||(0,u.J0)(!1),t),r=R(C.UseNavigateStable),a=l.useRef(!1);return w(()=>{a.current=!0}),l.useCallback(function(e,t){void 0===t&&(t={}),a.current&&("number"==typeof e?n.navigate(e):n.navigate(e,s({fromRouteId:r},t)))},[n,r])}():function(){g()||(0,u.J0)(!1);let e=l.useContext(c),{basename:t,future:n,navigator:r}=l.useContext(p),{matches:a}=l.useContext(d),{pathname:o}=y(),i=JSON.stringify((0,u.cm)(a,n.v7_relativeSplatPath)),s=l.useRef(!1);return w(()=>{s.current=!0}),l.useCallback(function(n,a){if(void 0===a&&(a={}),!s.current)return;if("number"==typeof n){r.go(n);return}let l=(0,u.pC)(n,JSON.parse(i),o,"path"===a.relative);null==e&&"/"!==t&&(l.pathname="/"===l.pathname?t:(0,u.RQ)([t,l.pathname])),(a.replace?r.replace:r.push)(l,a.state,a)},[t,r,i,o,e])}()}function x(e,t){let{relative:n}=void 0===t?{}:t,{future:r}=l.useContext(p),{matches:a}=l.useContext(d),{pathname:o}=y(),i=JSON.stringify((0,u.cm)(a,r.v7_relativeSplatPath));return l.useMemo(()=>(0,u.pC)(e,JSON.parse(i),o,"path"===n),[e,i,o,n])}var S=((r=S||{}).UseBlocker="useBlocker",r.UseRevalidator="useRevalidator",r.UseNavigateStable="useNavigate",r);var C=((a=C||{}).UseBlocker="useBlocker",a.UseLoaderData="useLoaderData",a.UseActionData="useActionData",a.UseRouteError="useRouteError",a.UseNavigation="useNavigation",a.UseRouteLoaderData="useRouteLoaderData",a.UseMatches="useMatches",a.UseRevalidator="useRevalidator",a.UseNavigateStable="useNavigate",a.UseRouteId="useRouteId",a);function R(e){var t;let n;let r=(t=0,(n=l.useContext(d))||(0,u.J0)(!1),n),a=r.matches[r.matches.length-1];return a.route.id||(0,u.J0)(!1),a.route.id}function U(){return R(C.UseRouteId)}function P(e){let{basename:t="/",children:n=null,location:r,navigationType:a=u.aU.Pop,navigator:o,static:i=!1,future:c}=e;g()&&(0,u.J0)(!1);let h=t.replace(/^\/*/,"/"),d=l.useMemo(()=>({basename:h,navigator:o,static:i,future:s({v7_relativeSplatPath:!1},c)}),[h,c,o,i]);"string"==typeof r&&(r=(0,u.cP)(r));let{pathname:m="/",search:v="",hash:y="",state:w=null,key:b="default"}=r,x=l.useMemo(()=>{let e=(0,u.Zn)(m,h);return null==e?null:{location:{pathname:e,search:v,hash:y,state:w,key:b},navigationType:a}},[h,m,v,y,w,b,a]);return null==x?null:l.createElement(p.Provider,{value:d},l.createElement(f.Provider,{children:n,value:x}))}(i||(i=n.t(l,2))).startTransition;var O=((o=O||{})[o.pending=0]="pending",o[o.success=1]="success",o[o.error=2]="error",o);new Promise(()=>{})}}]);