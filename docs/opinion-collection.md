# 多来源评分与评论采集

这是独立采集工具，先把公开资料收集到本地，再接入追番界面；不会修改关注、下载任务、播放文件，也不会向外部平台发帖或评分。

## 运行

```powershell
python -m pip install -r tools/reviews-requirements.txt
python tools/collect_opinions.py --state config/simple-tracking.json --output config/opinions --pages-per-source 5 --rounds 1000 --request-budget 450
```

默认采集当前全部关注条目，包括暂停关注的番。`--subject 253` 可以额外测试条目，不会关注或下载该番。先遍历所有条目的评分，再轮转采集评论，避免一部老番的大量评论阻塞其他来源。

每页提交后保存游标，重复运行会续抓、去重并补全长文。单次网络请求预算只是流量保护；达到预算会记录 `pending`，不能当成全量完成。已有完整长文不会被新抓到的列表摘要覆盖。成功任务缓存六小时，错误退避；明确使用 `--retry-network-errors` 可重试暂时的连接失败，不会清除登录、403、429 的退避或绕过验证。

## 数据与完整性

`config/opinions/opinions.db` 是本地 SQLite 资料库，`opinions.json` 是可导入的快照，`reviews.jsonl` 是逐条原文导出，`summary.json` 是来源统计。`ratings.csv` 可直接查看评分对照，`source-status.csv` 可逐条检查抓取完成度。来源统计分别列出评分记录数和实际有分数的条目数。

- `ratings`：Bangumi 条目 ID、来源、来源范围、原始分制、总分、投票数、分布、更新时间和原文链接。不同平台、正篇/SP、系列/单季的分数分别保存，不计算混合总分。零票为未评分，不伪造 0 分。
- `reviews`：平台内唯一 ID、长短评类型、作者公开信息、原文、作者评分、原始时间、原文链接、是否已取得完整正文。`content_complete=false` 明确表示摘要。剧透状态未知时保存为未知，不标成无剧透。
- `tasks`：每个条目/来源/任务的分页位置、是否完成、失败原因及重试时间。`complete` 只表示当前公开入口已遍历到末页，不包含私密、删除或平台未开放的内容。
- `mappings`：跨站 ID 及匹配证据。Kitsu 仅允许原文名精确匹配且年份兼容的唯一结果。多结果、映射冲突、SP 注释会等待核对，不用相似番名替代。
- `cache`：本地原始响应，便于复核页面结构与解析结果。抓取页面仅解析文本，不执行页面脚本。

导出及原始响应包含公开用户评论，仅保留本地；不要把整个资料库、配置或原文缓存提交到源码仓库。源码包只包含采集程序，不附第三方评论全集。

## 渠道

| 渠道 | 当前采集方式与边界 |
|---|---|
| Bangumi | 官方 `/v0/subjects/{id}` 获取总分、人数、分布、简介；公开短评和长评网页分页采集，另补长评正文。网页结构变化单独报错 |
| Kitsu | 官方 JSON:API 获取评分与分布、评论入口及跨站 ID。评论为空只代表该公开接口返回空列表 |
| MyAnimeList | Jikan 评分与评论分页；Jikan 连接失败时评分可尝试 MAL 原站结构化数据。未提供可用数据时明确记录失败，不使用不明镜像 |
| Bilibili | 已验证的媒体 ID 获取评分、短评与长评列表，按服务端游标分页；另尝试公开文章正文。正文不可用时保留摘要并记录状态，列表不冒充完整文章。风控返回立即记录，不绕过 |
| IMDb | 使用官方非商业评分数据集，缓存一天；只有有明确 IMDb ID 的条目才导入评分。数据集不包含评论，网页评论不可用不伪造 |
| 豆瓣 | 有明确条目 ID 时尝试公开页面结构化评分与内嵌评论。验证跳转或页面无数据会失败；不会自动登录、解验证码或声称已取全站评论 |
| TMDB | 支持环境变量 `TMDB_READ_TOKEN`。必须有 `tv/ID` 或 `movie/ID` 映射；系列总分单独标记，评论采集中文简繁、英文、日文，仍明确是语言子集 |
| AniDB / Anime-Planet / aniSearch | 有明确条目映射时尝试公开 JSON-LD；仅包含页面内嵌评论，状态为部分获取。AniDB 实测返回 403，不绕过 |
| AniList | 保留跨站 ID，未启用采集。其条款限制同类追番/列表服务，需先确认适用条件 |

可选导入 [bangumi-data](https://github.com/bangumi-data/bangumi-data) 发布的跨站映射：从其 README 说明的分发地址获取 `dist/data.json` 后指定 `--site-map 路径`。映射资料不等于评分数据，也不把其中标注 SP 的媒体链接当作正篇。

手动校验后的映射文件可使用 `--mappings 文件`：

```json
{"253":{"kitsu":"1","myanimelist":"1","imdb":"tt0213338","douban":"1424406"}}
```

IMDb 数据集只用于本地个人、非商业用途；发布或商业化前应重新核对各数据源的使用条件。

## 验证与来源

```powershell
python -m unittest discover -s tools -p test_collect_opinions.py -v
```

19 项测试覆盖中文解析、分页续传、跨来源去重、摘要/正文区分、SP 版本保护、映射冲突、网络预算、内容协商、无评分状态与多条目对应同一 IMDb 系列。

- [Bangumi 公共 API](https://bangumi.github.io/api/)
- [Kitsu API](https://kitsu.docs.apiary.io/)
- [Jikan API](https://docs.api.jikan.moe/)
- [TMDB 电视剧评论接口](https://developer.themoviedb.org/reference/tv-series-reviews)
- [IMDb 官方非商业数据集](https://developer.imdb.com/non-commercial-datasets/)
- [AniList 使用条款](https://docs.anilist.co/guide/terms-of-use)
