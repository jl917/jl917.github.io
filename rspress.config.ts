import * as path from "path";
import { defineConfig } from "rspress/config";

export default defineConfig({
  root: path.join(__dirname, "docs"),
  title: "Document",
  description: "Web Information",
  icon: "/rspress-icon.png",
  logoText: "Main",
  themeConfig: {
    socialLinks: [
      { icon: "github", mode: "link", content: "https://github.com/jl917" },
      {
        icon: "wechat",
        mode: "text",
        content: "wechat: _jl917",
      },
    ],
    enableScrollToTop: true,
  },
});
