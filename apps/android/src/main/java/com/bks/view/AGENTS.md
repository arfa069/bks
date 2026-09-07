# Android View 层指南

## 职责与依赖

- `app/` 保存 `MainActivity`、`BksApp`、UI composition 与 ViewModel factory；`navigation/` 保存根路由；`common/` 保存共享组件、表单、主题、视觉和界面侧平台回调。
- 普通 Screen 只接收 `UiState + actions + 必要平台回调`，不得以参数接收或在文件内装配 ViewModel、Repository、Gateway、Factory 或 `BksAppContainer`；ViewModel 获取与业务依赖装配只允许留在 `view/app` Route/composition root。Screen/Route 均禁止访问 Database、DAO、Room Entity、具体 Repository 或 Shared API wire DTO。
- Context、Intent、URI、MediaStore、文件 writer、系统设置 Launcher 与窗口控制留在 View；转换为稳定 action/result 后再交给 ViewModel。
- Compose `remember` 仅用于短暂展示状态；持久业务状态必须由 ViewModel/Model 提供。

## UI 与敏感流程

- 账号登录、本地模式、Session 与注销转换必须显式；失败不得残留 Token 或伪造成功。账号注销与本机数据删除互不隐式触发。
- 待确认确认/忽略/撤销必须使用稳定 ID；Screen 不得模拟持久化成功。账本报表只派生展示结果，不修改输入。
- 自动记账页分别展示用户开关、系统授权、服务连接与可公开读取的系统状态；临时权限故障不反写用户开关。
- 诊断入口和页面仅在 Debug 可见；页面全程 `FLAG_SECURE`，Release 在入口与路由两层都必须不可达。
- 保持 Material 3、现有返回层级、Snackbar 生命周期和共享表单行为；可见变化同步更新 Compose/Robolectric 测试。

## 验证

Screen/Route 测试只通过 state、actions 和平台 callback 装配；不得为测试重新引入 Container 或具体 Repository service locator。
