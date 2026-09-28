<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import { siteState } from '../../store/site'

/**
 * 导出内容备份。
 *
 * 这里导出的是一份「内容 JSON」：个人资料、技能、项目、图片索引、页面文案、简历元信息。
 * 图片文件和访问统计不在里面——前者是磁盘上的文件，后者是运行时数据，
 * 它们都归服务器上的每日整库备份（tar）管。
 */
const site = siteState()
const busy = ref(false)
const notice = ref('')
const error = ref('')

/** 让用户知道这份文件里有什么、没有什么，别指望它能恢复图片 */
const includes = computed(() => [
  ['包含', '个人资料、联系方式、技能分组与条目、证书、项目描述、成果图索引、页面文案、站点设置、简历元信息'],
  ['不包含', '图片和 PDF 文件本身（在服务器磁盘上），以及访问统计、下载日志、接口日志'],
  ['怎么恢复', '图片由服务器每日备份的 tar 包恢复；内容 JSON 是给人看的，方便对照找回某一版文字']
])

async function download() {
  busy.value = true
  error.value = ''
  try {
    const { blob, fileName } = await api.adminExportBackup()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName || 'portfolio-content.json'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10000)
    const kb = Math.round(blob.size / 1024)
    notice.value = `已导出（${kb} KB）`
  } catch (e) {
    error.value = e.message || '导出失败'
  } finally {
    busy.value = false
  }
}

/** 顺手把当前内容的几个数字列出来，导出前心里有数 */
const counts = ref(null)

async function loadCounts() {
  try {
    const [contacts, groups, certs, projects, resumes] = await Promise.all([
      api.adminListContacts(),
      api.adminListSkillGroups(),
      api.adminListCertificates(),
      api.adminListProjects(),
      api.adminListResumes()
    ])
    counts.value = [
      { label: '项目', value: projects.length },
      { label: '技能分组', value: groups.length },
      { label: '证书', value: certs.length },
      { label: '联系方式', value: contacts.length },
      { label: '简历', value: resumes.length }
    ]
  } catch {
    counts.value = null
  }
}

onMounted(loadCounts)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>导出备份</h1>
        <p class="sub">把当前全部内容导成一份 JSON，随时可以下载留底。</p>
      </div>
      <button class="btn btn--primary btn--sm" type="button" :disabled="busy" @click="download">
        {{ busy ? '导出中…' : '导出内容 JSON' }}
      </button>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="counts" class="stat-cards">
      <div v-for="c in counts" :key="c.label" class="stat-card">
        <div class="stat-card__label">{{ c.label }}</div>
        <div class="stat-card__value">{{ c.value }}</div>
      </div>
    </div>

    <div class="card">
      <h2>这份文件里有什么</h2>
      <table class="admin-table">
        <tbody>
          <tr v-for="row in includes" :key="row[0]">
            <th style="width: 90px">{{ row[0] }}</th>
            <td>{{ row[1] }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h2>服务器上的每日备份</h2>
      <p class="muted" style="line-height: 1.7">
        服务器每天凌晨会把数据库和图片目录打包成一个 tar，保留最近 7 份，放在
        <code>/opt/portfolio/backups</code>。它和这里导出的 JSON 是两回事：
        tar 包是「整站可还原」，JSON 是「内容能看、能对照」。
      </p>
      <p class="muted" style="margin-top: 8px">
        当前站点：{{ site.profile.name }} · {{ site.profile.school }}
      </p>
    </div>
  </div>
</template>
