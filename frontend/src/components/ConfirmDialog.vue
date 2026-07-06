<script setup lang="ts">
/**
 * Reusable confirm modal in the app's house style (see TagEditDialog): warning
 * panel, action button, Esc/back-drop to cancel. Controlled via `open`; emits
 * `confirm` / `cancel`. Pass `busy` while the action runs to lock the buttons.
 * Custom body via the default slot, otherwise `message` (newlines respected).
 *
 * Two flavours:
 * - danger (default): amber warning panel + red confirm — destructive actions.
 *   Focus lands on Cancel so Enter can never fall through onto the action.
 * - `danger=false`: neutral panel + primary confirm — bulk-but-safe actions.
 * - `input`: adds a text field (replaces window.prompt); confirm emits its
 *   value and is disabled while blank. Focus goes to the field, Enter submits.
 */
import { ref, watch, nextTick } from 'vue'
import { AlertTriangle, LoaderCircle, X } from 'lucide-vue-next'
import { useEscapeClose } from '@/composables/useEscapeClose'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  message?: string
  confirmLabel?: string
  busy?: boolean
  danger?: boolean
  /** Show a text input; `confirm` then carries its value. */
  input?: boolean
  inputPlaceholder?: string
  initialValue?: string
}>(), {
  title: 'Are you sure?',
  message: '',
  confirmLabel: 'Delete',
  busy: false,
  danger: true,
  input: false,
  inputPlaceholder: '',
  initialValue: '',
})
const emit = defineEmits<{ confirm: [value: string]; cancel: [] }>()

// Esc cancels — but not mid-action.
useEscapeClose(() => props.open, () => { if (!props.busy) emit('cancel') })

const cancelBtn = ref<HTMLButtonElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)
const inputValue = ref('')
watch(() => props.open, (open) => {
  if (!open) return
  inputValue.value = props.initialValue
  // Prompt mode: straight into the field. Danger mode: Cancel, so Enter can't
  // fall through onto the destructive action.
  nextTick(() => (props.input ? inputEl.value : cancelBtn.value)?.focus())
})

const canConfirm = () => !props.busy && (!props.input || inputValue.value.trim().length > 0)
function submit() { if (canConfirm()) emit('confirm', inputValue.value.trim()) }
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-[4000] flex items-center justify-center bg-black/50 backdrop-blur-sm p-4"
      @click.self="!busy && emit('cancel')">
      <div :role="danger ? 'alertdialog' : 'dialog'" aria-modal="true" aria-labelledby="confirm-dialog-title"
        class="w-full max-w-md bg-background border border-border rounded-xl shadow-xl overflow-hidden flex flex-col">
        <!-- Header -->
        <div class="flex items-center gap-2 px-4 py-3 border-b border-border">
          <h2 id="confirm-dialog-title" class="text-sm font-semibold">{{ title }}</h2>
          <button class="btn-icon ml-auto" :disabled="busy" @click="emit('cancel')"><X :size="14" /></button>
        </div>

        <!-- Body -->
        <div class="p-4 flex flex-col gap-3">
          <div v-if="danger"
            class="flex items-start gap-2 p-3 rounded border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/30 text-amber-700 dark:text-amber-300 text-xs">
            <AlertTriangle :size="16" class="flex-shrink-0 mt-0.5" />
            <div class="min-w-0">
              <slot><p class="whitespace-pre-line">{{ message }}</p></slot>
            </div>
          </div>
          <div v-else class="text-xs text-muted-fg">
            <slot><p class="whitespace-pre-line">{{ message }}</p></slot>
          </div>
          <input v-if="input" ref="inputEl" v-model="inputValue" type="text"
            class="w-full px-2.5 py-1.5 text-xs rounded border border-border bg-background focus:outline-none focus:ring-1 focus:ring-primary"
            :placeholder="inputPlaceholder" :disabled="busy" @keydown.enter.prevent="submit" />
        </div>

        <!-- Footer -->
        <div class="flex items-center justify-end gap-2 px-4 py-2.5 border-t border-border bg-muted/10">
          <button ref="cancelBtn"
            class="px-3 py-1 text-xs font-medium rounded border border-border text-muted-fg hover:text-foreground disabled:opacity-50"
            :disabled="busy" @click="emit('cancel')">
            Cancel
          </button>
          <button class="inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded disabled:opacity-50"
            :class="danger ? 'bg-red-600 text-white hover:bg-red-700' : 'bg-primary text-primary-fg hover:bg-primary/90'"
            :disabled="!canConfirm()" @click="submit">
            <LoaderCircle v-if="busy" :size="12" class="animate-spin" />
            {{ confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
