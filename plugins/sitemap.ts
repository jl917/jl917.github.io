import type { RspressPlugin } from "@rspress/shared";
import { statSync, writeFileSync } from "fs";

// https://www.w3.org/TR/NOTE-datetime
type ChangeFreq =
  | "always"
  | "hourly"
  | "daily"
  | "weekly"
  | "monthly"
  | "yearly"
  | "never";

type Priority =
  | "0.0"
  | "0.1"
  | "0.2"
  | "0.3"
  | "0.4"
  | "0.5"
  | "0.6"
  | "0.7"
  | "0.8"
  | "0.9"
  | "1.0";
interface Sitemap {
  loc: string;
  lastmod?: string;
  changefreq?: ChangeFreq;
  priority?: Priority;
}

interface CustomMaps {
  [prop: string]: Sitemap;
}

interface Options {
  customMaps?: CustomMaps;
  defaultPriority?: Priority;
  defaultChangeFreq?: ChangeFreq;
}

const generateNode = (sitemap: Sitemap): string => {
  let result = "<url>";
  for (const [tag, value] of Object.entries(sitemap)) {
    result += `<${tag}>${value}</${tag}>`;
  }
  result += "</url>";
  return result;
};

const generateXml = (sitemaps: Sitemap[]) => {
  return `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${sitemaps.reduce(
    (node, sitemap) => node + generateNode(sitemap),
    ""
  )}</urlset>`;
};
export default function rspressPluginSitemap(
  domain: string,
  options: Options = {}
): RspressPlugin {
  options = {
    customMaps: {},
    defaultChangeFreq: "monthly",
    defaultPriority: "0.5",
    ...options,
  };
  let sitemaps = [];
  return {
    name: "rspress-plugin-sitemap",
    config(config) {
      return config;
    },
    extendPageData(pageData, isProd) {
      if (isProd) {
        sitemaps.push({
          loc: `${domain}${pageData.routePath}`,
          lastmod: statSync(pageData._filepath).mtime.toISOString(),
          priority:
            pageData.routePath === "/" ? "1.0" : options.defaultPriority,
          changefreq: options.defaultChangeFreq,
          ...(options.customMaps[pageData.routePath] || {}),
        });
      }
    },
    afterBuild(config, isProd) {
      if (isProd) {
        writeFileSync(
          `./${
            config.builderConfig?.output?.distPath?.root || "doc_build"
          }/sitemap.xml`,
          generateXml(sitemaps)
        );
      }
    },
  };
}
