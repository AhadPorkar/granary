<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

/**
 * Checkboxes for the well-known privileges plus a read/write row per repository.
 * Privileges that fit neither (e.g. for a repository that no longer exists) are kept and listed as "other".
 */
const props = defineProps<{ modelValue: string[]; wellKnown: string[]; repositories: string[]; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [privileges: string[]] }>();

const { t, te } = useI18n();

const PRIVILEGE_KEYS: Record<string, string> = {
  'system:admin': 'systemAdmin',
  'repositories:manage': 'repositoriesManage',
  'users:manage': 'usersManage',
  'settings:manage': 'settingsManage',
  'repository:read:*': 'repositoryReadAll',
  'repository:write:*': 'repositoryWriteAll',
};

function label(privilege: string): string {
  const key = PRIVILEGE_KEYS[privilege];
  return key && te(`privileges.names.${key}`) ? t(`privileges.names.${key}`) : privilege;
}

function description(privilege: string): string {
  const key = PRIVILEGE_KEYS[privilege];
  return key && te(`privileges.descriptions.${key}`) ? t(`privileges.descriptions.${key}`) : '';
}

const selected = computed(() => new Set(props.modelValue));

const other = computed(() =>
  props.modelValue.filter((p) => {
    if (props.wellKnown.includes(p)) {
      return false;
    }
    const match = /^repository:(read|write):(.+)$/.exec(p);
    return !(match && match[2] !== '*' && props.repositories.includes(match[2]));
  }),
);

function has(privilege: string): boolean {
  return selected.value.has(privilege);
}

function toggle(privilege: string, on: boolean): void {
  const next = new Set(props.modelValue);
  if (on) {
    next.add(privilege);
  } else {
    next.delete(privilege);
  }
  emit('update:modelValue', [...next]);
}

function onCheckbox(privilege: string, event: Event): void {
  toggle(privilege, (event.target as HTMLInputElement).checked);
}

function slug(value: string): string {
  return value.replace(/[^a-zA-Z0-9]+/g, '-');
}
</script>

<template>
  <div class="stack" data-testid="privilege-picker">
    <fieldset class="privilege-group">
      <legend class="form-section__title">{{ t('privileges.wellKnown') }}</legend>
      <div class="privilege-grid">
        <label v-for="privilege in wellKnown" :key="privilege" class="checkbox">
          <input type="checkbox" :value="privilege" :checked="has(privilege)" :disabled="disabled" :data-privilege="privilege" @change="onCheckbox(privilege, $event)" />
          <span class="checkbox__text">
            <span>{{ label(privilege) }} <code class="faint">{{ privilege }}</code></span>
            <span v-if="description(privilege)" class="field__hint">{{ description(privilege) }}</span>
          </span>
        </label>
      </div>
    </fieldset>

    <fieldset class="privilege-group">
      <legend class="form-section__title">{{ t('privileges.perRepository') }}</legend>
      <p class="field__hint">{{ t('privileges.perRepositoryHint') }}</p>
      <div v-if="repositories.length" class="table-wrap">
        <table class="table table--nested privilege-table">
          <thead>
            <tr>
              <th scope="col">{{ t('privileges.repository') }}</th>
              <th scope="col">{{ t('privileges.read') }}</th>
              <th scope="col">{{ t('privileges.write') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="name in repositories" :key="name" :data-repository-row="name">
              <td>{{ name }}</td>
              <td>
                <input
                  :id="`priv-read-${slug(name)}`"
                  type="checkbox"
                  :checked="has(`repository:read:${name}`)"
                  :disabled="disabled"
                  :aria-label="t('privileges.readFor', { name })"
                  :data-privilege="`repository:read:${name}`"
                  @change="onCheckbox(`repository:read:${name}`, $event)"
                />
              </td>
              <td>
                <input
                  :id="`priv-write-${slug(name)}`"
                  type="checkbox"
                  :checked="has(`repository:write:${name}`)"
                  :disabled="disabled"
                  :aria-label="t('privileges.writeFor', { name })"
                  :data-privilege="`repository:write:${name}`"
                  @change="onCheckbox(`repository:write:${name}`, $event)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="muted small">{{ t('privileges.noRepositories') }}</p>
    </fieldset>

    <fieldset v-if="other.length" class="privilege-group">
      <legend class="form-section__title">{{ t('privileges.other') }}</legend>
      <div class="privilege-grid">
        <label v-for="privilege in other" :key="privilege" class="checkbox">
          <input type="checkbox" checked :disabled="disabled" :data-privilege="privilege" @change="onCheckbox(privilege, $event)" />
          <code>{{ privilege }}</code>
        </label>
      </div>
    </fieldset>
  </div>
</template>

<style scoped>
.privilege-group {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}

.privilege-group legend {
  padding: 0;
  margin-bottom: 0.5rem;
}
</style>
