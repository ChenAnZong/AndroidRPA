import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { status } from '@/store';
import {
  X, FolderPlus, Loader2,
} from 'lucide-react';
import { ideApi } from '@/services/api';

interface Props {
  imei: string;
  isOpen: boolean;
  onClose: () => void;
  onCreated: (name: string, path: string) => void;
}

const PROJECT_ROOT = '/storage/emulated/0/Yyds.Py';
const NAME_REGEX = /^[a-zA-Z0-9_\u4e00-\u9fff-]+$/;

const DEFAULT_MAIN_PY = `from yyds import *


def main():
    """Script entry"""
    pass


if __name__ == '__main__':
    main()
`;

const DEFAULT_UI_YML = `- type: text
  name: text-notice
  props:
    text: "Welcome to script"

- type: check
  name: check-enable
  props:
    text: "Enable feature"
    checked: true

- type: edit
  name: edit-user
  props:
    hint: "Enter username"
`;

function makeProjectConfig(name: string) {
  return `[default]\nPROJECT_NAME=${name}\nPROJECT_VERSION=1.0\nDEBUG_DEVICE_IP=127.0.0.1\n`;
}

export default function ProjectInitDialog({ imei, isOpen, onClose, onCreated }: Props) {
  const { t } = useTranslation(['ide', 'common']);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [nameError, setNameError] = useState('');

  const validateName = useCallback((v: string) => {
    if (!v.trim()) { setNameError(t('enterProjectName')); return false; }
    if (!NAME_REGEX.test(v)) { setNameError(t('nameFormatError')); return false; }
    setNameError('');
    return true;
  }, []);

  const handleCreate = useCallback(async () => {
    if (!validateName(name)) return;
    setCreating(true);
    const root = `${PROJECT_ROOT}/${name}`;

    try {
      // Create directory
      await ideApi.writeFile(imei, `${root}/.keep`, '');

      // Write default files
      await Promise.all([
        ideApi.writeFile(imei, `${root}/main.py`, DEFAULT_MAIN_PY),
        ideApi.writeFile(imei, `${root}/project.config`, makeProjectConfig(name)),
        ideApi.writeFile(imei, `${root}/ui.yml`, DEFAULT_UI_YML),
        ideApi.writeFile(imei, `${root}/requirements.txt`, 'requests\npyyaml\n'),
      ]);

      status.success(t('projectCreated', { name }));
      onCreated(name, root);
      onClose();
      setName('');
    } catch (e: any) {
      status.error(t('createFailed', { msg: e.message }));
    } finally {
      setCreating(false);
    }
  }, [name, imei, onCreated, onClose, validateName]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="bg-[#252526] rounded-lg border border-[#3c3c3c] w-[480px] max-h-[85vh] flex flex-col shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[#3c3c3c]">
          <div className="flex items-center gap-2">
            <FolderPlus size={16} className="text-[#007acc]" />
            <span className="text-[13px] font-medium text-white">{t('newProject')}</span>
          </div>
          <button onClick={onClose} className="p-1 hover:bg-[#3c3c3c] rounded"><X size={14} /></button>
        </div>

        <div className="flex-1 overflow-auto px-5 py-4 space-y-4">
          {/* Project name */}
          <div>
            <label className="block text-[11px] text-gray-400 mb-1">
              {t('projectNameLabel')} <span className="text-red-400">*</span>
            </label>
            <input
              className={`w-full bg-[#3c3c3c] text-white text-[12px] px-3 py-1.5 rounded border outline-none ${
                nameError ? 'border-red-500' : 'border-[#555] focus:border-[#007acc]'
              }`}
              value={name}
              onChange={(e) => { setName(e.target.value); if (nameError) validateName(e.target.value); }}
              placeholder="my_project"
              autoFocus
            />
            {nameError && <div className="text-[10px] text-red-400 mt-0.5">{nameError}</div>}
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-[#3c3c3c]">
          <button
            onClick={onClose}
            className="px-3 py-1.5 rounded text-[12px] bg-[#3c3c3c] hover:bg-[#4c4c4c] text-gray-300"
          >
            {t('common:cancel')}
          </button>
          <button
            onClick={handleCreate}
            disabled={creating || !name.trim()}
            className="flex items-center gap-1 px-4 py-1.5 rounded text-[12px] bg-[#007acc] hover:bg-[#005f9e] disabled:opacity-50 font-medium"
          >
            {creating ? <Loader2 size={12} className="animate-spin" /> : <FolderPlus size={12} />}
            {t('common:create')}
          </button>
        </div>
      </div>
    </div>
  );
}
