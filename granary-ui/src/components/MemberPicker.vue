<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import AppIcon from './AppIcon.vue';

/** Ordered member list of a group repository. Only repositories of the same format are offered. */
const props = defineProps<{ modelValue: string[]; candidates: string[]; format: string; id?: string }>();
const emit = defineEmits<{ 'update:modelValue': [members: string[]] }>();

const { t } = useI18n();
const selection = ref('');

const available = computed(() => props.candidates.filter((name) => !props.modelValue.includes(name)));

function add(): void {
  if (!selection.value || props.modelValue.includes(selection.value)) {
    return;
  }
  emit('update:modelValue', [...props.modelValue, selection.value]);
  selection.value = '';
}

function remove(name: string): void {
  emit('update:modelValue', props.modelValue.filter((m) => m !== name));
}

function move(name: string, delta: number): void {
  const members = [...props.modelValue];
  const index = members.indexOf(name);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= members.length) {
    return;
  }
  members.splice(index, 1);
  members.splice(target, 0, name);
  emit('update:modelValue', members);
}
</script>

<template>
  <div class="stack stack--tight" data-testid="member-picker">
    <ol v-if="modelValue.length" class="member-list">
      <li v-for="(name, index) in modelValue" :key="name" class="member-list__item">
        <span class="faint num">{{ index + 1 }}.</span>
        <span class="member-list__name">{{ name }}</span>
        <button type="button" class="btn btn--ghost btn--icon btn--sm" :disabled="index === 0" :aria-label="t('repositories.members.moveUp', { name })" @click="move(name, -1)">
          <AppIcon name="arrowUp" :size="14" />
        </button>
        <button type="button" class="btn btn--ghost btn--icon btn--sm" :disabled="index === modelValue.length - 1" :aria-label="t('repositories.members.moveDown', { name })" @click="move(name, 1)">
          <AppIcon name="arrowDown" :size="14" />
        </button>
        <button type="button" class="btn btn--danger-ghost btn--icon btn--sm" :aria-label="t('repositories.members.remove', { name })" @click="remove(name)">
          <AppIcon name="close" :size="14" />
        </button>
      </li>
    </ol>
    <p v-else class="muted small" style="margin: 0">{{ t('repositories.members.empty') }}</p>

    <div v-if="available.length" class="input-group">
      <select :id="id" v-model="selection" class="select" :aria-label="t('repositories.members.add')">
        <option value="">{{ t('common.select') }}</option>
        <option v-for="name in available" :key="name" :value="name">{{ name }}</option>
      </select>
      <button type="button" class="btn" :disabled="!selection" @click="add">
        <AppIcon name="plus" :size="16" />
        {{ t('common.add') }}
      </button>
    </div>
    <p v-else-if="!candidates.length" class="muted small" style="margin: 0">
      {{ t('repositories.members.noCandidates', { format }) }}
    </p>
  </div>
</template>
