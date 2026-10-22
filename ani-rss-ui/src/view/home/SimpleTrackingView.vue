<template>
  <section class="tracking" v-loading="loading">
    <header class="page-header">
      <div><div class="eyebrow">ANI-RSS · 极简追番</div><h1>关注就好，更新交给我。</h1><p>自动补齐，持续追更。更喜欢的版本准备好了，再替你换上。</p></div>
      <div class="actions"><el-button @click="refresh" :loading="refreshing">检查更新</el-button><el-button @click="openSettings">基础配置</el-button></div>
    </header>

    <el-alert v-if="!state.settings.credentialSaved" title="先连接一次下载器，设置资源库与极影视播放目录，之后直接点追番。" type="info" :closable="false" show-icon/>
    <el-alert v-else-if="!state.settings.trustedGroups.length && !state.settings.allowOtherChinese && !state.shows.some(show => show.preferredGroups.length)" title="还没有认可的字幕组。可以先关注番剧，再结合实际资源添加偏好；程序会等待，不会随便下载。" type="info" :closable="false" show-icon/>
    <p v-else-if="state.settings.initialGroup" class="hint">先看 {{ state.settings.initialGroup }}，随后按偏好升级。{{ state.settings.preferSimplified ? '同等条件优先简体，繁体也可。' : '' }}{{ state.settings.allowOtherChinese ? '名单内暂无资源时，寻找其他明确标注中文字幕的版本。' : '' }}</p>

    <div class="toolbar">
      <el-radio-group v-model="tab" @change="changeTab"><el-radio-button value="following">正在追 · {{ state.shows.length }}</el-radio-button><el-radio-button value="discover">找番</el-radio-button></el-radio-group>
      <el-input v-model="query" placeholder="搜索番名，或粘贴 Bangumi 链接" clearable class="search" @keyup.enter="search"><template #append><el-button @click="search" :loading="searching">搜索</el-button></template></el-input>
    </div>

    <div v-if="tab === 'following'" class="show-grid">
      <article v-for="show in state.shows" :key="show.id" class="show-card" @click="openDetail(show.id)">
        <div class="cover"><img v-if="show.image" :src="show.image" :alt="show.title" loading="lazy"><span v-else>追</span><div class="cover-count">{{ Object.keys(show.selected).length }}{{ show.totalEpisodes ? ` / ${show.totalEpisodes}` : '' }} 集可看</div></div>
        <div class="card-info"><h2>{{ show.title }}</h2><p class="status">{{ show.message }}</p><p v-if="downloading(show).length" class="download-text">{{ downloading(show).length }} 个版本下载中 · {{ Math.round(downloading(show)[0].progress) }}%</p><div class="card-foot"><span>{{ show.enabled ? '自动追番' : '已暂停' }}</span><span>版本与目录 →</span></div></div>
      </article>
      <el-empty v-if="!state.shows.length" description="还没有关注的番剧。找到喜欢的，点一下追番。"><el-button type="primary" @click="tab='discover'; discover()">去找番</el-button></el-empty>
    </div>

    <div v-else class="discovery-list" v-loading="searching">
      <div class="discovery-heading"><div><strong>{{ sourceMode === 'mikan' ? '蜜柑新番列表' : 'Bangumi 搜索结果' }}</strong><p v-if="sourceMode === 'mikan'">每 10 分钟自动更新 · {{ catalog.checkedAt ? `最近同步 ${formatTime(catalog.checkedAt)}` : '正在获取番剧列表' }}<span v-if="catalog.refreshing"> · 同步中</span></p></div><span>{{ visibleResults.length }} 部番剧</span></div>
      <el-alert v-if="sourceMode === 'mikan' && catalog.error" :title="catalog.error" type="warning" :closable="false" show-icon/>
      <el-radio-group v-if="sourceMode === 'mikan'" v-model="weekday" class="weekday-filter" size="small"><el-radio-button v-for="day in weekdays" :key="day" :value="day">{{ day }}</el-radio-button></el-radio-group>
      <div class="anime-list">
        <article v-for="show in visibleResults" :key="`${show.source || 'bgm'}-${show.id}`" class="anime-row">
          <img v-if="show.image" :src="show.image" :alt="show.title" loading="lazy" class="list-cover"><div v-else class="list-cover cover-placeholder">番</div>
          <div class="list-title"><h2>{{ show.title }}</h2><p>{{ show.weekday || show.subtitle }}</p></div>
          <div v-if="show.source === 'mikan'" class="list-update"><span>蜜柑资源更新</span><strong>{{ show.updated || '暂无发布' }}</strong><a :href="show.url" target="_blank" rel="noopener noreferrer">蜜柑详情 ↗</a></div>
          <el-button type="primary" :disabled="isDiscoveryFollowed(show)" :loading="following === show.id" @click="follow(show)">{{ isDiscoveryFollowed(show) ? '已在追' : '追番' }}</el-button>
        </article>
      </div>
      <el-empty v-if="!visibleResults.length && !searching" :description="catalog.refreshing ? '首次列表同步中，稍后会自动显示。' : '没有匹配的番剧，试试其他名称或星期。'"/>
      <p class="hint">番剧与资源更新日期来自蜜柑计划；更新日期不代表已有认可字幕组的中文字幕。</p>
    </div>

    <el-dialog v-model="settingsOpen" title="一次配置，持续追番" width="min(620px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top" :model="settings">
        <el-form-item label="qBittorrent WebUI 地址"><el-input v-model="settings.downloadHost" placeholder="http://极空间地址:端口"/></el-form-item>
        <div class="form-pair"><el-form-item label="用户名"><el-input v-model="settings.username" autocomplete="username"/></el-form-item><el-form-item label="密码或 API 密钥"><el-input v-model="settings.credential" type="password" show-password autocomplete="new-password" :placeholder="state.settings.credentialSaved ? '留空保留已有凭据' : '填写密码或 qbt_ 开头的密钥'"/></el-form-item></div>
        <el-form-item label="本机可访问的资源库目录"><el-input v-model="settings.libraryRoot"/><div class="hint">下载的原始版本和被替换的版本保存在这里。远程 NAS 可填写映射盘路径。</div></el-form-item>
        <el-form-item label="下载器内的资源库路径（远程 / Docker 时填写）"><el-input v-model="settings.downloaderRoot" placeholder="例如 /downloads/ani-rss-simple；同机同路径时留空"/><div class="hint">对应上方同一文件夹。程序会自动换算两端路径，不再把 Windows 路径发给 NAS。</div></el-form-item>
        <el-form-item label="极影视正式播放目录"><el-input v-model="settings.playbackRoot"/><div class="hint">极影视只关联这里的番剧文件夹。两处目录都必须能被本程序访问，资源库与下载器的路径须一致。</div></el-form-item>
        <el-form-item label="认可的字幕组（从最喜欢到可接受，每行一个）"><el-input v-model="trustedText" type="textarea" :rows="4" placeholder="结合番剧详情里的字幕组名称填写；留空时等待，不自动下载。"/></el-form-item>
        <el-form-item label="首次可看版本优先字幕组"><el-input v-model="settings.initialGroup" placeholder="例如 ANi；留空按上方偏好选择"/><div class="hint">先下载这个组的可接受版本，之后才补下载更偏好的版本。</div></el-form-item>
        <el-form-item><el-switch v-model="settings.allowOtherChinese" active-text="名单内暂无资源时，允许其他中文字幕组"/></el-form-item>
        <el-form-item><el-switch v-model="settings.preferSimplified" active-text="同等字幕组优先简体，繁体也接受"/></el-form-item>
        <el-form-item label="同时下载"><el-input-number v-model="settings.simultaneousDownloads" :min="1" :max="10"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="test" :loading="testing" :disabled="!state.settings.credentialSaved">测试已保存的连接</el-button><el-button type="primary" @click="saveSettings" :loading="saving">保存配置</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailOpen" :title="detail?.show.title || '番剧详情'" width="min(1040px, 96vw)" :close-on-click-modal="false">
      <div v-if="detail" class="detail">
        <div class="path-box"><strong>在极影视分类中关联这个文件夹</strong><div class="path-line"><code>{{ detail.playbackPath }}</code><el-button size="small" @click="copyPath">复制路径</el-button></div><p>首次关联一次，后续新集与版本切换沿用同一目录。{{ detail.show.message }}</p></div>
        <el-collapse><el-collapse-item title="本番偏好与追番状态" name="preferences">
          <el-form label-position="top"><el-form-item label="本番认可字幕组（按偏好排序；留空沿用基础配置）"><el-input v-model="preferenceText" type="textarea" :rows="3"/></el-form-item><p class="hint">已发现字幕组：<el-button v-for="group in discoveredGroups" :key="group" link type="primary" @click="addGroup(group)">{{ group }}</el-button><span v-if="!discoveredGroups.length">暂未发现可识别的单集资源</span></p><div class="preference-controls"><el-switch v-model="preferences.fastest" active-text="着急看：优先最早发布的可接受版本"/><el-switch v-model="preferences.enabled" active-text="自动追番"/><el-button type="primary" @click="savePreferences" :loading="saving">保存偏好</el-button></div></el-form>
        </el-collapse-item></el-collapse>
        <p class="hint">已下载的旧版本保留在资源库。手动切换后该集会固定，点「恢复自动」即可继续自动升级。</p>
        <div v-for="episode in episodes" :key="episode.key" class="episode">
          <div class="episode-heading"><strong>第 {{ episode.key }} 集</strong><el-tag v-if="detail.show.pinnedEpisodes.includes(episode.key)" type="warning" size="small">手动固定</el-tag><el-button v-if="detail.show.pinnedEpisodes.includes(episode.key)" link type="primary" @click="automatic(episode.key)">恢复自动</el-button></div>
          <div v-for="release in episode.releases" :key="release.id" class="release">
            <div class="release-info"><div><strong>{{ release.group }}</strong> <el-tag v-if="detail.show.selected[episode.key] === release.id" size="small" type="success">正式版本</el-tag> <el-tag v-if="!release.chinese" size="small" type="info">未明确标注中文</el-tag> <el-tag v-else-if="!allowed(release)" size="small" type="info">尚未认可</el-tag></div><p :title="release.title">{{ release.title }}</p><el-progress v-if="release.status==='downloading'" :percentage="Math.min(100, Math.round(release.progress))"/><p v-if="release.error" class="error">{{ release.error }}</p></div>
            <div class="release-actions"><span>{{ statusLabel(release) }}</span><el-button v-if="release.status === 'ready' && detail.show.selected[episode.key] !== release.id" size="small" :disabled="!allowed(release)" :loading="working === release.id" @click="versionAction('choose', release)">切换到此版本</el-button><el-button v-else-if="['available','error'].includes(release.status)" size="small" :disabled="!allowed(release)" :loading="working === release.id" @click="versionAction('download', release)">下载备选</el-button></div>
          </div>
        </div>
        <el-empty v-if="!episodes.length" description="正在寻找单集资源；找不到时会继续等待并自动检查。"/>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import {computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import api from '@/js/api.js'

const state = ref({settings: {trustedGroups: []}, shows: []})
const loading = ref(true), refreshing = ref(false), searching = ref(false), following = ref(''), saving = ref(false), testing = ref(false), working = ref('')
const tab = ref('following'), query = ref(''), results = ref([]), settingsOpen = ref(false), detailOpen = ref(false), detail = ref(null)
const settings = reactive({}), preferences = reactive({}), trustedText = ref(''), preferenceText = ref('')
const catalog = ref({items: [], checkedAt: 0, refreshing: false, error: ''}), weekday = ref('全部'), sourceMode = ref('mikan'), followedMikan = ref({})
const weekdays = computed(() => ['全部', ...new Set(catalog.value.items.map(show => show.weekday))])
const visibleResults = computed(() => {
  if(sourceMode.value !== 'mikan') return results.value
  const keyword = query.value.trim().toLocaleLowerCase()
  return catalog.value.items.filter(show => (weekday.value === '全部' || show.weekday === weekday.value) && (!keyword || show.title.toLocaleLowerCase().includes(keyword)))
    .map(show => ({...show, source:'mikan'}))
})
const formatTime = timestamp => new Date(timestamp).toLocaleString('zh-CN',{hour12:false})
const isDiscoveryFollowed = show => show.source === 'mikan' ? isFollowed(followedMikan.value[show.id] || catalog.value.subjectIds?.[show.id]) || state.value.shows.some(s => s.title === show.title) : isFollowed(show.id)
let timer
const post = (name, body) => api.post(`api/simple/${name}`, body)
const lines = text => [...new Set(text.split('\n').map(v => v.trim()).filter(Boolean))]
const downloading = show => show.releases.filter(r => ['submitting','downloading'].includes(r.status))
const isFollowed = id => state.value.shows.some(show => show.id === String(id))
const allowed = release => release.chinese && (detail.value.groups.some(g => g.toLowerCase() === release.group.toLowerCase()) || state.value.settings.allowOtherChinese && !detail.value.show.preferredGroups.length)
const discoveredGroups = computed(() => [...new Set(detail.value?.show.releases.map(r => r.group) || [])].sort())
const episodes = computed(() => {
  const groups = new Map()
  for (const release of detail.value?.show.releases || []) {
    const key = String(release.episode)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(release)
  }
  return [...groups].sort((a,b) => Number(a[0])-Number(b[0])).map(([key,releases]) => ({key,releases: releases.sort((a,b) => {
    const rank = r => {const i=detail.value.groups.findIndex(g=>g.toLowerCase()===r.group.toLowerCase()); return i<0 ? 999 : i}
    return rank(a)-rank(b) || a.publishedAt-b.publishedAt
  })}))
})
const statusLabel = release => ({available:'可按需下载', submitting:'正在提交', downloading:`下载中 ${Math.round(release.progress)}%`, ready:'已在资源库', error:'等待重试'}[release.status] || release.status)

async function load() {
  try {state.value = (await post('state')).data; if (detailOpen.value && detail.value) await loadDetail(detail.value.show.id); if(tab.value==='discover' && sourceMode.value==='mikan' && !searching.value) await discover(false)} finally {loading.value=false}
}
async function loadDetail(id) {detail.value=(await api.post(`api/simple/detail?id=${encodeURIComponent(id)}`)).data}
async function openDetail(id) {
  await loadDetail(id)
  preferenceText.value=detail.value.show.preferredGroups.join('\n')
  Object.assign(preferences,{showId:id, fastest:detail.value.show.fastest, enabled:detail.value.show.enabled})
  detailOpen.value=true
}
function openSettings() {Object.assign(settings,state.value.settings); trustedText.value=settings.trustedGroups.join('\n'); settingsOpen.value=true}
async function saveSettings() {
  saving.value=true
  try {await post('settings',{...settings,trustedGroups:lines(trustedText.value)}); await load(); settingsOpen.value=false; ElMessage.success('已保存，将自动检查资源')} finally {saving.value=false}
}
async function test() {testing.value=true; try {await post('test'); ElMessage.success('下载器连接成功')} finally {testing.value=false}}
async function refresh() {refreshing.value=true; try {await post('refresh'); ElMessage.success('已开始检查，进度会自动刷新')} finally {refreshing.value=false}}
async function discover(showLoading=true) {
  if(showLoading) searching.value=true
  try {
    catalog.value=(await post('discover')).data
    sourceMode.value='mikan'
  } finally {if(showLoading) searching.value=false}
}
function changeTab(value) {if(value==='discover') discover().catch(()=>{})}
async function search() {
  if(!query.value.trim()) {tab.value='discover'; return discover()}
  tab.value='discover'; searching.value=true
  try {
    const match=query.value.match(/(?:bgm\.tv|bangumi\.tv|chii\.in)\/subject\/(\d+)/)
    if(match) {
      sourceMode.value='bangumi'
      const {data}=await api.post(`api/animeGardenList?bgmUrl=${encodeURIComponent(query.value.trim())}`)
      results.value=data.flatMap(w=>w.subjects.map(s=>({id:String(s.id),title:s.name,image:s.cover,subtitle:'Bangumi'})))
    } else {
      weekday.value='全部'
      await discover(false)
    }
  } finally {searching.value=false}
}
async function follow(show) {following.value=show.id; try {if(show.source==='mikan') {const {data}=await post('follow-mikan',{mikanId:show.id}); followedMikan.value[show.id]=data.id} else await post('follow',{subjectId:show.id}); await load(); ElMessage.success('已关注，会自动补齐并持续追更')} finally {following.value=''}}
function addGroup(group) {const values=lines(preferenceText.value); if(!values.includes(group)) preferenceText.value=[...values,group].join('\n')}
async function savePreferences() {saving.value=true; try {await post('preferences',{...preferences,groups:lines(preferenceText.value)}); await load(); ElMessage.success('偏好已保存')} finally {saving.value=false}}
async function versionAction(action,release) {
  working.value=release.id
  try {await post(action,{showId:detail.value.show.id,releaseId:release.id}); await load(); ElMessage.success(action==='choose'?'已切换，原版本仍保留在资源库':'已提交备选下载')} finally {working.value=''}
}
async function automatic(episode) {await post('automatic',{showId:detail.value.show.id,episode}); await load()}
async function copyPath() {
  try {await navigator.clipboard.writeText(detail.value.playbackPath); ElMessage.success('路径已复制')}
  catch {ElMessage.info('浏览器不允许复制，请选中上方路径手动复制')}
}
function startPolling() {if(!timer) timer=setInterval(()=>load().catch(()=>{}),10000)}
function stopPolling() {clearInterval(timer); timer=undefined}
onMounted(()=>{load().catch(()=>{}); startPolling()})
onActivated(startPolling)
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.discovery-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.discovery-heading>span{font-size:13px;color:var(--el-text-color-secondary)}.weekday-filter{margin:12px 0 20px;display:flex;flex-wrap:wrap;gap:5px}.anime-list{border:1px solid var(--el-border-color-lighter);border-radius:14px;background:var(--el-bg-color);overflow:hidden}.anime-row{display:flex;align-items:center;gap:22px;padding:18px 24px;border-bottom:1px solid var(--el-border-color-lighter)}.anime-row:last-child{border-bottom:0}.list-cover{width:58px;height:80px;object-fit:cover;border-radius:6px;flex-shrink:0}.cover-placeholder{display:flex;align-items:center;justify-content:center;background:var(--el-fill-color)}.list-title{flex:1;min-width:0}.list-title h2{margin:0;font-size:16px}.list-title p{margin:5px 0 0}.list-update{display:flex;flex-direction:column;gap:5px;width:145px;flex-shrink:0}.list-update span,.list-update a{font-size:12px;color:var(--el-text-color-secondary)}.list-update strong{font-size:13px;font-weight:500}.list-update a{text-decoration:none;color:var(--el-color-primary)}
@media(max-width:600px){.anime-row{padding:15px 12px;gap:12px;flex-wrap:wrap}.list-title{flex-basis:calc(100% - 85px)}.list-update{margin-left:70px;flex:1}.list-cover{width:52px;height:74px}.anime-row>.el-button{margin-left:auto}.discovery-heading{align-items:flex-start}.weekday-filter{gap:4px}}
.tracking{height:100%;overflow:auto;box-sizing:border-box;padding:40px clamp(18px,4vw,64px);background:var(--el-bg-color-page)}
.page-header{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:28px}.eyebrow{font-size:12px;color:var(--el-color-primary);letter-spacing:2px;font-weight:600}h1{font-size:clamp(25px,3vw,36px);letter-spacing:-1px;margin:12px 0}p{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7;margin:8px 0}.actions{display:flex;white-space:nowrap}.toolbar{display:flex;justify-content:space-between;gap:20px;margin:28px 0}.search{max-width:420px}.show-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:20px;padding-bottom:30px}.show-grid>.el-empty{grid-column:1/-1;min-height:300px}.show-card{background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:16px;overflow:hidden;display:flex;cursor:pointer;min-height:180px;transition:box-shadow .2s}.show-card:hover{box-shadow:0 8px 25px #00000010}.cover{width:116px;flex-shrink:0;position:relative;background:var(--el-fill-color);display:flex;align-items:center;justify-content:center;font-size:38px;color:var(--el-text-color-placeholder)}.cover img{width:100%;height:100%;object-fit:cover;position:absolute}.cover-count{position:absolute;bottom:0;left:0;right:0;background:#0009;color:white;font-size:12px;padding:10px;text-align:center}.card-info{padding:18px;min-width:0;display:flex;flex-direction:column;flex:1}h2{font-size:16px;line-height:1.5;margin:0 0 8px}.status{font-size:12px}.download-text{color:var(--el-color-primary)}.card-foot{display:flex;justify-content:space-between;gap:8px;margin-top:auto;font-size:11px;color:var(--el-text-color-secondary);padding-top:15px}.discovery .el-button{margin-top:auto;align-self:flex-start}.form-pair{display:grid;grid-template-columns:1fr 1.5fr;gap:16px}.hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}.path-box{padding:18px;background:var(--el-fill-color-light);border-radius:10px;margin-bottom:18px}.path-line{display:flex;align-items:center;gap:15px;margin-top:12px}.path-line code{overflow-wrap:anywhere;user-select:all;flex:1;font-size:13px}.preference-controls{display:flex;flex-wrap:wrap;gap:20px;align-items:center}.episode{margin-top:22px}.episode-heading{display:flex;gap:12px;align-items:center;margin-bottom:8px}.release{display:flex;justify-content:space-between;gap:18px;padding:15px 0;border-bottom:1px solid var(--el-border-color-lighter)}.release-info{min-width:0;flex:1}.release-info p{font-size:12px;overflow-wrap:anywhere}.release-actions{display:flex;flex-direction:column;align-items:flex-end;justify-content:center;gap:8px;font-size:12px;flex-shrink:0;color:var(--el-text-color-secondary)}.error{color:var(--el-color-danger)}
@media(max-width:800px){.tracking{padding:24px 16px}.page-header{display:block}.actions{margin-top:18px}.toolbar{flex-direction:column}.search{max-width:none}.form-pair{grid-template-columns:1fr}.show-grid{grid-template-columns:1fr}.path-line{flex-wrap:wrap}.release{gap:8px}.release-actions{max-width:105px}}
</style>
