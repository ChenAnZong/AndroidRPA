# VSCode 插件发布指南

## 前置准备

### 1. 创建发布者账号

访问 [Visual Studio Marketplace](https://marketplace.visualstudio.com/manage) 创建发布者账号：

1. 使用 Microsoft 账号登录
2. 点击 "Create publisher"
3. 填写发布者信息：
   - **Publisher ID**: `ChenAnzong` (必须与 package.json 中的 publisher 字段一致)
   - **Display Name**: 显示名称
   - **Description**: 发布者描述

### 2. 创建 Personal Access Token (PAT)

1. 访问 https://dev.azure.com/
2. 点击右上角用户图标 → "Personal access tokens"
3. 点击 "+ New Token"
4. 配置 Token：
   - **Name**: `vsce-publish-token`
   - **Organization**: All accessible organizations
   - **Expiration**: 自定义（建议 90 天或更长）
   - **Scopes**: 
     - 选择 "Custom defined"
     - 勾选 **Marketplace** → **Manage** (完全访问权限)
5. 点击 "Create"
6. **重要**: 复制生成的 Token（只显示一次）

### 3. 准备插件图标

在 `resources/` 目录创建 `icon.png`：
- 尺寸: 128x128 像素
- 格式: PNG
- 建议: 透明背景或深色背景

### 4. 更新 package.json 中的 repository URL

将 `package.json` 中的以下字段更新为实际的 GitHub 仓库地址：
```json
"repository": {
  "type": "git",
  "url": "https://github.com/ChenAnZong/AndroidRPA.git"
}
```

## 发布步骤

### 方法一：使用 vsce 命令行工具（推荐）

#### 1. 安装 vsce

```bash
npm install -g @vscode/vsce
```

#### 2. 登录发布者账号

```bash
vsce login ChenAnzong
```

输入刚才创建的 Personal Access Token。

#### 3. 打包插件

```bash
npm run package
vsce package
```

这会生成 `yyds-auto-dev-plugin-1.0.0.vsix` 文件。

#### 4. 发布到市场

```bash
vsce publish
```

或者指定版本号发布：
```bash
vsce publish 1.0.0
```

或者发布并自动增加版本号：
```bash
vsce publish patch  # 1.0.0 -> 1.0.1
vsce publish minor  # 1.0.0 -> 1.1.0
vsce publish major  # 1.0.0 -> 2.0.0
```

### 方法二：通过 Web 界面手动上传

1. 访问 https://marketplace.visualstudio.com/manage/publishers/ChenAnzong
2. 点击 "+ New extension" → "Visual Studio Code"
3. 上传 `.vsix` 文件
4. 填写必要信息并提交

## 发布后

### 验证发布

1. 访问插件页面：https://marketplace.visualstudio.com/items?itemName=ChenAnzong.yyds-auto-dev-plugin
2. 在 VS Code 中搜索 "Yyds.Auto" 验证可安装

### 更新插件

修改代码后更新版本：

```bash
# 更新版本号（自动修改 package.json）
npm version patch  # 或 minor, major

# 重新打包
npm run package

# 发布更新
vsce publish
```

## 常见问题

### Q: 发布失败，提示 "Publisher not found"
**A**: 确保 package.json 中的 `publisher` 字段与创建的发布者 ID 完全一致。

### Q: 发布失败，提示 "Missing repository"
**A**: 在 package.json 中添加 `repository` 字段，或使用 `--allow-missing-repository` 参数：
```bash
vsce publish --allow-missing-repository
```

### Q: 图标不显示
**A**: 确保 `icon` 字段路径正确，且图标文件为 128x128 PNG 格式。

### Q: 如何撤销发布
**A**: 在 Marketplace 管理页面可以 unpublish 插件，但已安装的用户仍可使用。

## 自动化发布（可选）

可以在 package.json 添加发布脚本：

```json
"scripts": {
  "publish:patch": "npm version patch && vsce publish",
  "publish:minor": "npm version minor && vsce publish",
  "publish:major": "npm version major && vsce publish"
}
```

使用：
```bash
npm run publish:patch
```

## 注意事项

1. ✅ 确保所有代码已编译（`npm run package`）
2. ✅ 测试插件功能正常
3. ✅ 更新 CHANGELOG.md
4. ✅ 提交所有代码到 Git
5. ✅ 创建 Git tag（可选）：`git tag v1.0.0 && git push --tags`
6. ⚠️ Personal Access Token 保密，不要提交到代码仓库
7. ⚠️ 首次发布可能需要几分钟审核时间

## 相关链接

- [VS Code 插件发布文档](https://code.visualstudio.com/api/working-with-extensions/publishing-extension)
- [vsce 工具文档](https://github.com/microsoft/vscode-vsce)
- [Marketplace 管理页面](https://marketplace.visualstudio.com/manage)
- [Azure DevOps PAT 管理](https://dev.azure.com/)
