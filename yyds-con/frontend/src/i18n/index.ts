import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

import zhCN_common from './locales/zh-CN/common.json';
import zhCN_dashboard from './locales/zh-CN/dashboard.json';
import zhCN_device from './locales/zh-CN/device.json';
import zhCN_ide from './locales/zh-CN/ide.json';
import zhCN_devtool from './locales/zh-CN/devtool.json';
import zhCN_flow from './locales/zh-CN/flow.json';
import zhCN_designer from './locales/zh-CN/designer.json';
import zhCN_pip from './locales/zh-CN/pip.json';
import zhCN_apk from './locales/zh-CN/apk.json';
import zhCN_schedule from './locales/zh-CN/schedule.json';
import zhCN_auth from './locales/zh-CN/auth.json';
import zhCN_log from './locales/zh-CN/log.json';
import zhCN_file from './locales/zh-CN/file.json';

import en_common from './locales/en/common.json';
import en_dashboard from './locales/en/dashboard.json';
import en_device from './locales/en/device.json';
import en_ide from './locales/en/ide.json';
import en_devtool from './locales/en/devtool.json';
import en_flow from './locales/en/flow.json';
import en_designer from './locales/en/designer.json';
import en_pip from './locales/en/pip.json';
import en_apk from './locales/en/apk.json';
import en_schedule from './locales/en/schedule.json';
import en_auth from './locales/en/auth.json';
import en_log from './locales/en/log.json';
import en_file from './locales/en/file.json';

import ja_common from './locales/ja/common.json';
import ja_dashboard from './locales/ja/dashboard.json';
import ja_device from './locales/ja/device.json';
import ja_ide from './locales/ja/ide.json';
import ja_devtool from './locales/ja/devtool.json';
import ja_flow from './locales/ja/flow.json';
import ja_designer from './locales/ja/designer.json';
import ja_pip from './locales/ja/pip.json';
import ja_apk from './locales/ja/apk.json';
import ja_schedule from './locales/ja/schedule.json';
import ja_auth from './locales/ja/auth.json';
import ja_log from './locales/ja/log.json';
import ja_file from './locales/ja/file.json';

import zhTW_common from './locales/zh-TW/common.json';
import zhTW_dashboard from './locales/zh-TW/dashboard.json';
import zhTW_device from './locales/zh-TW/device.json';
import zhTW_ide from './locales/zh-TW/ide.json';
import zhTW_devtool from './locales/zh-TW/devtool.json';
import zhTW_flow from './locales/zh-TW/flow.json';
import zhTW_designer from './locales/zh-TW/designer.json';
import zhTW_pip from './locales/zh-TW/pip.json';
import zhTW_apk from './locales/zh-TW/apk.json';
import zhTW_schedule from './locales/zh-TW/schedule.json';
import zhTW_auth from './locales/zh-TW/auth.json';
import zhTW_log from './locales/zh-TW/log.json';
import zhTW_file from './locales/zh-TW/file.json';

export const resources = {
  'zh-CN': {
    common: zhCN_common, dashboard: zhCN_dashboard, device: zhCN_device,
    ide: zhCN_ide, devtool: zhCN_devtool, flow: zhCN_flow, designer: zhCN_designer,
    pip: zhCN_pip, apk: zhCN_apk, schedule: zhCN_schedule, auth: zhCN_auth,
    log: zhCN_log, file: zhCN_file,
  },
  en: {
    common: en_common, dashboard: en_dashboard, device: en_device,
    ide: en_ide, devtool: en_devtool, flow: en_flow, designer: en_designer,
    pip: en_pip, apk: en_apk, schedule: en_schedule, auth: en_auth,
    log: en_log, file: en_file,
  },
  ja: {
    common: ja_common, dashboard: ja_dashboard, device: ja_device,
    ide: ja_ide, devtool: ja_devtool, flow: ja_flow, designer: ja_designer,
    pip: ja_pip, apk: ja_apk, schedule: ja_schedule, auth: ja_auth,
    log: ja_log, file: ja_file,
  },
  'zh-TW': {
    common: zhTW_common, dashboard: zhTW_dashboard, device: zhTW_device,
    ide: zhTW_ide, devtool: zhTW_devtool, flow: zhTW_flow, designer: zhTW_designer,
    pip: zhTW_pip, apk: zhTW_apk, schedule: zhTW_schedule, auth: zhTW_auth,
    log: zhTW_log, file: zhTW_file,
  },
} as const;

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: 'zh-CN',
    defaultNS: 'common',
    ns: ['common', 'dashboard', 'device', 'ide', 'devtool', 'flow', 'designer', 'pip', 'apk', 'schedule', 'auth', 'log', 'file'],
    interpolation: { escapeValue: false },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'i18nextLng',
    },
  });

export default i18n;
