<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import ImageField from '../../components/admin/ImageField.vue'

/**
 * 项目管理：列表 + 编辑 + 成果图。
 *
 * 和别处不一样的一点：slug 建好之后不允许改（它是前台地址的一部分，
 * 改了会让已发出的链接失效），后端也会拦。所以编辑表单里它是只读的。
 */
const projects = ref([])
const loading = ref(true)
const notice = ref('')
const error = ref('')

/** 0 = 新增，其它 = 编辑中的项目 id，null = 关闭 */
const editingId = ref(null)
const form = reactive({
  slug: '',
  name: '',
  type: '',
  period: '',
  role: '',
  summary: '',
  description: '',
  tags: '',
  highlights: '',
  repoUrl: '',
  repoLabel: '',
  sortOrder: 100,
  visible: true
})

/** 编辑中项目的成果图 */
const images = ref([])
const newImage = reactive({ imageKey: '', caption: '', sortOrder: 100, visible: true })
/** 新增的是图片还是短片，决定上传白名单和预览方式 */
const newImageKind = ref('image')

/** 展示位里既能放图也能放短片，按扩展名区分（前台也是这么判的） */
const isVideoKey = (key) => /\.(mp4|webm)$/i.test(key || '')

function flash(msg) {
  notice.value = msg
  setTimeout(() => {
    if (notice.value === msg) notice.value = ''
  }, 2400)
}

async function load() {
  loading.value = true
  try {
    projects.value = (await api.adminListProjects()) || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function startCreate() {
  editingId.value = 0
  Object.assign(form, {
    slug: '',
    name: '',
    type: '后端项目',
    period: '',
    role: '',
    summary: '',
    description: '',
    tags: '',
    highlights: '',
    repoUrl: '',
    repoLabel: '',
    sortOrder: (projects.value.length + 1) * 10,
    visible: true
  })
  images.value = []
}

async function startEdit(item) {
  startCreate()
  editingId.value = item.id
  // 列表接口不带成果图，编辑时单独取一次
  const detail = await api.adminGetProject(item.id)
  Object.assign(form, detail)
  images.value = (await api.adminListProjectImages(detail.slug)) || []
}

async function submit() {
  if (!form.name.trim()) {
    error.value = '项目名不能为空'
    return
  }
  if (editingId.value === 0 && !/^[a-zA-Z0-9-]{1,64}$/.test(form.slug.trim())) {
    error.value = 'slug 只能用字母、数字和短横线，且不超过 64 位'
    return
  }
  try {
    if (editingId.value === 0) {
      const created = await api.adminCreateProject({ ...form })
      projects.value.push(created)
      editingId.value = created.id
      flash('已新增，接着可以加成果图')
    } else {
      const saved = await api.adminUpdateProject(editingId.value, { ...form })
      const idx = projects.value.findIndex((p) => p.id === editingId.value)
      if (idx >= 0) projects.value[idx] = saved
      flash('已保存')
    }
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function remove(item) {
  if (!confirm(`删除项目「${item.name}」？它的成果图记录会一起删掉（图片文件保留）。`)) return
  try {
    await api.adminDeleteProject(item.id)
    projects.value = projects.value.filter((p) => p.id !== item.id)
    if (editingId.value === item.id) editingId.value = null
    flash('已删除')
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

async function addImage() {
  if (!newImage.imageKey) {
    error.value = '先选一张图片'
    return
  }
  try {
    const created = await api.adminCreateProjectImage({
      ...newImage,
      projectSlug: form.slug,
      sortOrder: (images.value.length + 1) * 10
    })
    images.value.push(created)
    Object.assign(newImage, { imageKey: '', caption: '', sortOrder: 100, visible: true })
    flash('已添加成果图')
  } catch (e) {
    error.value = e.message || '添加失败'
  }
}

async function saveImage(img) {
  try {
    await api.adminUpdateProjectImage(img.id, img)
    flash('已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function removeImage(img) {
  if (!confirm('删除这张成果图？')) return
  try {
    await api.adminDeleteProjectImage(img.id)
    images.value = images.value.filter((i) => i.id !== img.id)
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

/** 上下移动：和后一条/前一条交换 sortOrder，然后各自保存 */
async function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= images.value.length) return
  const list = [...images.value]
  const a = list[index]
  const b = list[target]
  const tmp = a.sortOrder
  a.sortOrder = b.sortOrder
  b.sortOrder = tmp
  list[index] = b
  list[target] = a
  images.value = list
  try {
    await Promise.all([api.adminUpdateProjectImage(a.id, a), api.adminUpdateProjectImage(b.id, b)])
  } catch (e) {
    error.value = e.message || '排序保存失败'
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>项目与成果图</h1>
        <p class="sub">描述、亮点、标签都用纯文本 + 换行，和前台渲染方式一致。</p>
      </div>
      <button class="btn btn--primary btn--sm" type="button" @click="startCreate">新增项目</button>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="loading" class="skeleton" style="height: 300px"></div>

    <template v-else>
      <div v-if="editingId !== null" class="card">
        <h2>
          {{ editingId === 0 ? '新增项目' : '编辑项目' }}
          <span v-if="editingId !== 0" class="muted">slug = {{ form.slug }}（不可改）</span>
        </h2>

        <div class="grid-2">
          <div class="field">
            <label>slug（前台地址 /projects/<span style="color: var(--ink-900)">{{ form.slug || '...' }}</span>）</label>
            <input v-model="form.slug" class="input" type="text" :disabled="editingId !== 0" placeholder="只能字母数字短横线">
          </div>
          <div class="field">
            <label>项目名</label>
            <input v-model="form.name" class="input" type="text">
          </div>
          <div class="field">
            <label>类型</label>
            <input v-model="form.type" class="input" type="text" placeholder="后端项目 / 学院项目 / 其他实践">
            <p class="muted" style="margin-top: 5px">「其他实践」会被归到首页下方的小列表里</p>
          </div>
          <div class="field">
            <label>时间</label>
            <input v-model="form.period" class="input" type="text" placeholder="2026.02 — 2026.03">
          </div>
          <div class="field">
            <label>角色</label>
            <input v-model="form.role" class="input" type="text">
          </div>
          <div class="field">
            <label>排序（小的在前）</label>
            <input v-model.number="form.sortOrder" class="input" type="number">
          </div>
        </div>

        <div class="field">
          <label>一句话简介</label>
          <input v-model="form.summary" class="input" type="text">
        </div>

        <div class="field">
          <label>详细描述（一行一段）</label>
          <textarea v-model="form.description" class="input" rows="4"></textarea>
        </div>

        <div class="field">
          <label>技术标签（一行一个，列表页最多显示 6 个）</label>
          <textarea v-model="form.tags" class="input" rows="3"></textarea>
        </div>

        <div class="field">
          <label>个人产出（一行一条，详情页「个人产出」列表）</label>
          <textarea v-model="form.highlights" class="input" rows="3"></textarea>
        </div>

        <div class="grid-2">
          <div class="field">
            <label>仓库地址</label>
            <input v-model="form.repoUrl" class="input" type="text" placeholder="留空表示不公开">
          </div>
          <div class="field">
            <label>仓库标签</label>
            <input v-model="form.repoLabel" class="input" type="text" placeholder="GitHub 开源 / 代码不公开">
          </div>
        </div>

        <div class="row">
          <label class="row" style="gap: 6px">
            <input v-model="form.visible" type="checkbox">
            <span>前台可见</span>
          </label>
          <button class="btn btn--primary btn--sm" type="button" @click="submit">保存项目</button>
          <button class="btn btn--ghost btn--sm" type="button" @click="editingId = null">收起</button>
          <RouterLink
            v-if="editingId !== 0"
            class="btn btn--ghost btn--sm"
            :to="`/projects/${form.slug}`"
            target="_blank"
          >
            看前台
          </RouterLink>
        </div>

        <template v-if="editingId !== 0">
          <h2 style="margin-top: 22px">成果图 / 短片</h2>
          <table class="admin-table">
            <thead>
              <tr>
                <th style="width: 90px">预览</th>
                <th>说明文字</th>
                <th style="width: 70px">排序</th>
                <th style="width: 60px">显示</th>
                <th style="width: 170px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(img, i) in images" :key="img.id">
                <td>
                  <video
                    v-if="isVideoKey(img.imageKey)"
                    class="thumb"
                    :src="`/files/${img.imageKey}`"
                    muted
                    loop
                    playsinline
                    preload="metadata"
                  ></video>
                  <img v-else class="thumb" :src="`/files/${img.imageKey}`" :alt="img.caption">
                </td>
                <td><input v-model="img.caption" class="input" type="text"></td>
                <td><input v-model.number="img.sortOrder" class="input" type="number"></td>
                <td style="text-align: center"><input v-model="img.visible" type="checkbox"></td>
                <td class="nowrap">
                  <button class="link-btn" type="button" @click="saveImage(img)">保存</button>
                  <button class="link-btn" type="button" :disabled="i === 0" @click="move(i, -1)">↑</button>
                  <button class="link-btn" type="button" :disabled="i === images.length - 1" @click="move(i, 1)">↓</button>
                  <button class="link-btn link-btn--danger" type="button" @click="removeImage(img)">删除</button>
                </td>
              </tr>
              <tr v-if="!images.length">
                <td colspan="5" class="muted">还没有成果图</td>
              </tr>
            </tbody>
          </table>

          <h2 style="margin-top: 18px">加一个展示位</h2>
          <div class="row" style="margin-bottom: 10px">
            <label class="row" style="gap: 6px">
              <input v-model="newImageKind" type="radio" value="image">
              <span>图片</span>
            </label>
            <label class="row" style="gap: 6px">
              <input v-model="newImageKind" type="radio" value="video">
              <span>短片（演示多端适配那种）</span>
            </label>
          </div>
          <ImageField
            v-model="newImage.imageKey"
            :kind="newImageKind"
            :label="newImageKind === 'video' ? '选择短片' : '选择图片'"
          />
          <div class="row">
            <input
              v-model="newImage.caption"
              class="input"
              style="flex: 1"
              type="text"
              :placeholder="newImageKind === 'video' ? '这段演示说明了什么' : '这张图说明了什么'"
            >
            <button class="btn btn--ghost btn--sm" type="button" @click="addImage">添加</button>
          </div>
        </template>
      </div>

      <div class="card">
        <table class="admin-table">
          <thead>
            <tr>
              <th style="width: 70px">排序</th>
              <th>项目</th>
              <th style="width: 110px">类型</th>
              <th style="width: 150px">时间</th>
              <th style="width: 60px">显示</th>
              <th style="width: 150px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in projects" :key="item.id">
              <td class="num">{{ item.sortOrder }}</td>
              <td>
                <div>{{ item.name }}</div>
                <div class="muted">{{ item.slug }}</div>
              </td>
              <td class="muted">{{ item.type }}</td>
              <td class="muted">{{ item.period }}</td>
              <td>{{ item.visible ? '是' : '否' }}</td>
              <td class="nowrap">
                <button class="link-btn" type="button" @click="startEdit(item)">编辑</button>
                <RouterLink class="link-btn" :to="`/projects/${item.slug}`" target="_blank">预览</RouterLink>
                <button class="link-btn link-btn--danger" type="button" @click="remove(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
