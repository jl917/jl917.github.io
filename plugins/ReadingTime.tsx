import React, { useState, useEffect } from "react";
import { usePageData, useLang, useDark } from "rspress/runtime";

import type { ReadTimeResults } from "reading-time";
import type { PresetLocale, WithDefaultLocale } from "rspress-plugin-devkit";

interface RspressReadingTimeComponentProps extends WithDefaultLocale {}

type PresetLocalePro = PresetLocale | "ko-KR";

const presetReadingTimeBuilder: Record<
  PresetLocalePro | "ko-KR",
  (result: ReadTimeResults) => string
> = {
  "zh-CN": (result: ReadTimeResults) => {
    return `预计阅读时间: ${
      result.minutes >= 1 ? `${Math.ceil(result.minutes)} 分钟` : "小于 1 分钟"
    }`;
  },
  "en-US": (result: ReadTimeResults) => {
    return `Estimated reading time: ${
      result.minutes >= 1
        ? `${Math.ceil(result.minutes)} minutes`
        : "less than 1 minute"
    }`;
  },
  "ko-KR": (result: ReadTimeResults) => {
    return `${Math.ceil(result.minutes)}min read`;
  },
};

function getReadingTimeText(
  result: ReadTimeResults,
  lang: string,
  defaultLocale: PresetLocalePro
) {
  const langKey = Object.keys(presetReadingTimeBuilder).includes(lang)
    ? lang
    : defaultLocale;

  return presetReadingTimeBuilder[langKey](result);
}

export const RspressReadingTimeComponent: React.FC<
  RspressReadingTimeComponentProps
> = (props) => {
  const { defaultLocale = "ko-KR" } = props;
  const pageData = usePageData();
  const pageReadingTime = pageData.page.readingTimeData as ReadTimeResults;

  const lang = useLang();
  const dark = useDark();

  const [readingTime, setReadingTime] = useState<string>(
    getReadingTimeText(pageReadingTime, lang, defaultLocale)
  );

  useEffect(() => {
    setReadingTime(getReadingTimeText(pageReadingTime, lang, defaultLocale));
  }, [lang, pageReadingTime]);

  return (
    <span data-dark={String(dark)} style={{ fontSize: 12 }}>
      {readingTime}
    </span>
  );
};

export default RspressReadingTimeComponent;
