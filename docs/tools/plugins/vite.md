# vite

### sample code

```js
export default function myPlugin() {
  return {
    name: "my-plugin", // 插件名称
    enforce: "pre", //调整插件被执行顺序
    apply: "build | serve", // 指定插件应用情景
    options(options) {},
    buildStart(options) {},
    resolveId(id) {},
    load(id) {},
    transform(src, id) {},
    buildEnd(error) {},
    closeBundle() {},
    config(config, env) {},
    configResolved(config) {},
    configureServer(server) {},
    transformIndexHtml(html, ctx) {},
    handleHotUpdate(ctx) {},
  };
}
```
