# vitest

#### vite.config.js

```js
import { defineConfig } from "vite";
import path from "path";
import react from "@vitejs/plugin-react";

export default defineConfig({
  root: "./src",
  build: {
    outDir: "../dist",
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  plugins: [react()],
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: "./test/setup.ts",
    css: true,
  },
});
```

### package.json

```json
{
  "scripts": {
    "test": "vitest",
    "coverage": "vitest run --coverage"
  },
  "devDependencies": {
    "@testing-library/react": "^13.3.0",
    "@vitejs/plugin-react": "^1.1.4",
    "jsdom": "^20.0.0",
    "vite": "^2.7.13",
    "vitest": "^0.22.1"
  }
}
```

### src/test/setup.ts

```ts
import { expect } from "vitest";

const toMatchAllText = (props, textList) => {
  let isMatchText = true;
  let noMatchtextList = [];

  for (const text of textList) {
    if (!props.innerHTML.includes(text)) {
      isMatchText = false;
      noMatchtextList.push(text);
    }
  }
  if (isMatchText) {
    return {
      message: () => "success",
      pass: true,
    };
  }
  return {
    message: () => `not match some text "${noMatchtextList.join(", ")}"`,
    pass: false,
  };
};

expect.extend({ toMatchAllText });
```
