import { ref } from 'vue';
import { defineStore } from 'pinia';
import { api } from '@/api';
import type { SystemInfo } from '@/api/types';

/** Public server information (name, version, flags) shared by the login page and the shell. */
export const useSystemStore = defineStore('system', () => {
  const info = ref<SystemInfo | null>(null);
  const loading = ref(false);

  async function loadInfo(force = false): Promise<SystemInfo | null> {
    if (info.value && !force) {
      return info.value;
    }
    if (loading.value) {
      return info.value;
    }
    loading.value = true;
    try {
      info.value = await api.system.info();
    } catch {
      /* the server may be unreachable; the UI still renders */
    } finally {
      loading.value = false;
    }
    return info.value;
  }

  return { info, loading, loadInfo };
});
