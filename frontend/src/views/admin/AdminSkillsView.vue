<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import ImageField from '../../components/admin/ImageField.vue'

/**
 * 技能分组管理。左侧选分组，右侧改这个分组的条目和关联项目。
 *
 * 条目是纯文本列表（一行一条），关联项目从现有项目里勾——勾选即建关联、取消即删关联，
 * 所以这里不能用「整组一起保存」的方式，改成单条增删。
 */
const groups = ref([])
const activeId = ref(null)
const items = ref([])
const related = ref([])
const allProjects = ref([])
const loading = ref(true)
const notice = ref('')
const error = ref('')

const newItemText = ref('')
const draftGroup = reactive({ category: '', sortOrder: 100, visible: true })

const activeGroup = () => groups.value.find((g) => g.id === activeId.value) || null

function flash(msg) {
  notice.value = msg
  setTimeout(() => {
    if (notice.value === msg) notice.value = ''
  }, 2400)
}

async function loadGroups(keepActive = false) {
  groups.value = (await api.adminListSkillGroups()) || []
  if (!keepActive || !activeGroup()) {
    activeId.value = groups.value[0]?.id ?? null
  }
  await loadDetail()
}

async function loadDetail() {
  if (!activeId.value) {
    items.value = []
    related.value = []
    return
  }
  const [it, rl] = await Promise.all([
    api.adminListSkillItems(activeId.value),
    api.adminListSkillRelated(activeId.value)
  ])
  items.value = it || []
  related.value = rl || []
}

async function init() {
  loading.value = true
  try {
    allProjects.value = (await api.adminListProjects()) || []
    await loadGroups()
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function selectGroup(id) {
  activeId.value = id
  await loadDetail()
}

async function createGroup() {
  if (!draftGroup.category.trim()) {
    error.value = '请填分组名称'
    return
  }
  try {
    const created = await api.adminCreateSkillGroup({ ...draftGroup })
    draftGroup.category = ''
    await loadGroups(true)
    activeId.value = created.id
    await loadDetail()
    flash('已新建分组')
  } catch (e) {
    error.value = e.message || '新建失败'
  }
}

async function saveGroup() {
  const g = activeGroup()
  if (!g) return
  try {
    await api.adminUpdateSkillGroup(g.id, g)
    flash('已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function removeGroup() {
  const g = activeGroup()
  if (!g) return
  if (!confirm(`删除分组「${g.category}」？它下面的技能条目和关联项目会一起删掉。`)) return
  try {
    await api.adminDeleteSkillGroup(g.id)
    activeId.value = null
    await loadGroups()
    flash('已删除')
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

async function addItem() {
  const text = newItemText.value.trim()
  if (!text || !activeId.value) return
  try {
    const created = await api.adminCreateSkillItem({
      groupId: activeId.value,
      text,
      sortOrder: (items.value.length + 1) * 10
    })
    items.value.push(created)
    newItemText.value = ''
  } catch (e) {
    error.value = e.message || '添加失败'
  }
}

async function saveItem(item) {
  try {
    await api.adminUpdateSkillItem(item.id, item)
    flash('已保存')
  } catch (e) {
    error.value = e.message || '保存失败'
  }
}

async function removeItem(item) {
  try {
    await api.adminDeleteSkillItem(item.id)
    items.value = items.value.filter((i) => i.id !== item.id)
  } catch (e) {
    error.value = e.message || '删除失败'
  }
}

/** 勾选/取消关联项目。用 select 的 change 触发，不用 v-model，避免出现「改了但没提交」的中间态 */
async function toggleRelated(slug, checked) {
  const existing = related.value.find((r) => r.projectSlug === slug)
  if (checked && !existing) {
    try {
      const created = await api.adminCreateSkillRelated({
        groupId: activeId.value,
        projectSlug: slug,
        sortOrder: (related.value.length + 1) * 10
      })
      related.value.push(created)
    } catch (e) {
      error.value = e.message || '关联失败'
    }
  } else if (!checked && existing) {
    try {
      await api.adminDeleteSkillRelated(existing.id)
      related.value = related.value.filter((r) => r.id !== existing.id)
    } catch (e) {
      error.value = e.message || '取消关联失败'
    }
  }
}

const isRelated = (slug) => related.value.some((r) => r.projectSlug === slug)

onMounted(init)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>技能分组</h1>
        <p class="sub">首页「专业技能」区。点开卡片时会显示这里关联的项目，以及分组下的证书。</p>
      </div>
    </div>

    <div v-if="notice" class="alert alert--ok">{{ notice }}</div>
    <div v-if="error" class="alert alert--error">{{ error }}</div>

    <div v-if="loading" class="skeleton" style="height: 300px"></div>

    <div v-else class="skill-admin">
      <div class="card skill-admin__side">
        <h2>分组</h2>
        <button
          v-for="g in groups"
          :key="g.id"
          class="skill-admin__item"
          :class="{ 'is-active': g.id === activeId }"
          type="button"
          @click="selectGroup(g.id)"
        >
          {{ g.category }}
          <span v-if="!g.visible" class="muted">（隐藏）</span>
        </button>

        <div class="skill-admin__new">
          <input v-model="draftGroup.category" class="input" type="text" placeholder="新分组名称">
          <button class="btn btn--ghost btn--sm" type="button" @click="createGroup">新建</button>
        </div>
      </div>

      <div class="skill-admin__main">
        <div v-if="!activeGroup()" class="card muted">左边选一个分组，或者新建一个。</div>

        <template v-else>
          <div class="card">
            <h2>分组设置</h2>
            <div class="grid-2">
              <div class="field">
                <label>分组名称</label>
                <input v-model="activeGroup().category" class="input" type="text">
              </div>
              <div class="field">
                <label>排序（小的在前）</label>
                <input v-model.number="activeGroup().sortOrder" class="input" type="number">
              </div>
            </div>
            <div class="row">
              <label class="row" style="gap: 6px">
                <input v-model="activeGroup().visible" type="checkbox">
                <span>前台可见</span>
              </label>
              <button class="btn btn--primary btn--sm" type="button" @click="saveGroup">保存分组</button>
              <button class="btn btn--ghost btn--sm link-btn--danger" type="button" @click="removeGroup">
                删除分组
              </button>
            </div>
          </div>

          <div class="card">
            <h2>技能条目 <span class="muted">一行一条，前台按顺序显示</span></h2>
            <table class="admin-table">
              <thead>
                <tr>
                  <th>内容</th>
                  <th style="width: 80px">排序</th>
                  <th style="width: 110px"></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in items" :key="item.id">
                  <td><input v-model="item.text" class="input" type="text"></td>
                  <td><input v-model.number="item.sortOrder" class="input" type="number"></td>
                  <td class="nowrap">
                    <button class="link-btn" type="button" @click="saveItem(item)">保存</button>
                    <button class="link-btn link-btn--danger" type="button" @click="removeItem(item)">删除</button>
                  </td>
                </tr>
                <tr v-if="!items.length">
                  <td colspan="3" class="muted">还没有条目</td>
                </tr>
              </tbody>
            </table>

            <div class="row" style="margin-top: 10px">
              <input
                v-model="newItemText"
                class="input"
                style="flex: 1"
                type="text"
                placeholder="新增一条技能，回车即可"
                @keyup.enter="addItem"
              >
              <button class="btn btn--ghost btn--sm" type="button" @click="addItem">添加</button>
            </div>
          </div>

          <div class="card">
            <h2>关联项目 <span class="muted">勾上即关联，取消即解除</span></h2>
            <div class="rel-grid">
              <label v-for="p in allProjects" :key="p.slug" class="rel-item">
                <input
                  type="checkbox"
                  :checked="isRelated(p.slug)"
                  @change="toggleRelated(p.slug, $event.target.checked)"
                >
                <span>{{ p.name }}</span>
                <span class="muted">{{ p.type }}</span>
              </label>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skill-admin { display: grid; grid-template-columns: 210px 1fr; gap: 14px; align-items: start; }
.skill-admin__side { position: sticky; top: 12px; }
.skill-admin__item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  margin-bottom: 3px;
  font-size: 13.5px;
  font-family: inherit;
  text-align: left;
  color: var(--ink-700);
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius);
  cursor: pointer;
}
.skill-admin__item:hover { background: var(--bg-blue); }
.skill-admin__item.is-active {
  color: var(--blue-700);
  background: var(--blue-100);
  border-color: var(--blue-200);
  font-weight: 700;
}
.skill-admin__new { display: flex; gap: 6px; margin-top: 12px; }
.skill-admin__main { min-width: 0; }
.rel-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 6px 12px; }
.rel-item { display: flex; align-items: center; gap: 7px; font-size: 13.5px; }
@media (max-width: 820px) {
  .skill-admin { grid-template-columns: 1fr; }
  .skill-admin__side { position: static; }
}
</style>
