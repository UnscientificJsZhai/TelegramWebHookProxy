import {useRef, useState} from 'react';
import {
    Alert,
    Box,
    Button,
    Chip,
    Divider,
    FormControlLabel,
    MenuItem,
    Stack,
    Switch,
    TextField,
    Typography
} from '@mui/material';
import KeyOutlined from '@mui/icons-material/KeyOutlined';
import PsychologyOutlined from '@mui/icons-material/PsychologyOutlined';
import TuneOutlined from '@mui/icons-material/TuneOutlined';
import {useSettings} from '../settingsContext';
import {useModels} from '../useModels';
import {resolveModelSelection} from '../modelSelection';
import {type AISettings, type AppSettings, buildAiPatch, DEFAULT_AI_SETTINGS, utf8Length} from '../settings';
import {isSettingsConflict, type VersionedSettings} from '../settingsClient';
import PageHeader from '../components/PageHeader';
import SectionCard from '../components/SectionCard';
import SecretField from '../components/SecretField';
import SettingsGate from '../components/SettingsGate';
import UnsavedChangesGuard from '../components/UnsavedChangesGuard';
import McpServers from '../components/McpServers';
import ModelSelector from '../components/ModelSelector';
import {FeedbackSnackbar, type Notice, SettingsConflictDialog} from '../components/Feedback';
import SkillCatalog from './Skill';

export default function Agent() {
    return <><PageHeader title="AI Agent 与技能管理"
                         description="配置 Telegram 私聊 AI 服务、模型与会话策略，管理供 Agent 读取的提示词技能。"/><SettingsGate>{snapshot =>
        <AgentSettings initial={snapshot}/>}</SettingsGate></>;
}

function AgentSettings({initial}: { initial: VersionedSettings<AppSettings> }) {
    const {update, reload, loading} = useSettings();
    const [saved, setSaved] = useState(initial);
    const [draft, setDraft] = useState(initial.settings.ai ?? DEFAULT_AI_SETTINGS);
    const [interval, setInterval] = useState(String(draft.autoCleanContextIntervalMinutes));
    const [saving, setSaving] = useState<string | null>(null);
    const [notice, setNotice] = useState<Notice | null>(null);
    const [conflict, setConflict] = useState(false);
    const [modelReloadEpoch, setModelReloadEpoch] = useState(0);
    const [modelDraft, setModelDraft] = useState<string | null>(null);
    const models = useModels(saved.settings, modelReloadEpoch);
    const [mcpEditorEpoch, setMcpEditorEpoch] = useState(0);
    const [mcpEditing, setMcpEditing] = useState(false);
    const [skillEditing, setSkillEditing] = useState(false);
    const lock = useRef(false);
    const baseline = saved.settings.ai ?? DEFAULT_AI_SETTINGS;
    const credentials = {
        provider: draft.provider,
        geminiApiKey: draft.geminiApiKey,
        openAiApiKey: draft.openAiApiKey,
        openAiBaseUrl: draft.openAiBaseUrl
    };
    const credentialsDirty = (Object.keys(credentials) as (keyof typeof credentials)[]).some(key => draft[key] !== baseline[key]);
    const policyKeys = ['agentEnabled', 'agentChatId', 'globalContext', 'silentContextCleanup'] as const;
    const policyDirty = policyKeys.some(key => draft[key] !== baseline[key]) || interval !== String(baseline.autoCleanContextIntervalMinutes);
    const {
        value: selectedModel,
        dirty: modelDirty
    } = resolveModelSelection(modelDraft, baseline.selectedModel, models.currentModel);
    const dirty = credentialsDirty || policyDirty || modelDirty;
    const intervalValid = /^(0|[1-9]\d*)$/.test(interval) && Number(interval) <= 2147483647;
    const contextBytes = utf8Length(draft.globalContext);
    const keyBytes = utf8Length(draft.provider === 'GEMINI' ? draft.geminiApiKey : draft.openAiApiKey);
    const set = <K extends keyof AISettings>(key: K, value: AISettings[K]) => setDraft(previous => ({
        ...previous,
        [key]: value
    }));

    const saveGroup = async (patch: Partial<AISettings>, group: string): Promise<boolean> => {
        if (!saved.etag || lock.current) return false;
        lock.current = true;
        setSaving(group);
        try {
            const next = await update(buildAiPatch(saved.settings.ai, patch), saved.etag);
            const nextAi = next.settings.ai ?? DEFAULT_AI_SETTINGS;
            setSaved(next);
            setDraft(previous => ({
                ...previous,
                ...Object.fromEntries(Object.keys(patch).map(key => [key, nextAi[key as keyof AISettings]])),
                ...(group === 'credentials' ? {selectedModel: nextAi.selectedModel} : {}),
            }));
            if (group === 'credentials') setModelReloadEpoch(previous => previous + 1);
            if (group === 'credentials' || group === 'model') setModelDraft(null);
            if (group === 'policy') setInterval(String(nextAi.autoCleanContextIntervalMinutes));
            setNotice({
                message: group === 'credentials' ? '服务凭据已保存，请选择模型。' : '配置已保存',
                severity: 'success'
            });
            return true;
        } catch (error) {
            if (isSettingsConflict(error)) setConflict(true);
            else setNotice({message: '保存失败，请检查此分区配置。未保存的内容已保留。', severity: 'error'});
            return false;
        } finally {
            lock.current = false;
            setSaving(null);
        }
    };
    const loadLatest = async () => {
        try {
            const next = await reload();
            setSaved(next);
            setDraft(next.settings.ai ?? DEFAULT_AI_SETTINGS);
            setInterval(String(next.settings.ai?.autoCleanContextIntervalMinutes ?? 0));
            setMcpEditorEpoch(previous => previous + 1);
            setModelReloadEpoch(previous => previous + 1);
            setModelDraft(null);
            setMcpEditing(false);
            setConflict(false);
        } catch {
            setNotice({message: '读取失败，当前草稿已保留。', severity: 'error'});
        }
    };
    const disabled = !!saving || !saved.etag;
    const saveButton = (group: string, label: string, changed: boolean, onSave: () => void, invalid = false) => <Stack
        direction="row" justifyContent="space-between" alignItems="center" gap={1} sx={{mt: 2.5}}><Typography
        variant="caption"
        color={changed ? 'primary' : 'text.secondary'}>{changed ? '有未保存修改' : '已与配置同步'}</Typography><Button
        variant="contained" size="small" disabled={disabled || !changed || invalid}
        onClick={onSave}>{saving === group ? '正在保存…' : label}</Button></Stack>;

    return <>
        {!saved.etag && <Alert severity="error" sx={{mb: 2}} action={<Button
            onClick={() => setConflict(true)}>重新读取</Button>}>未取得配置版本，暂时无法保存。</Alert>}
        <Box sx={{
            display: 'grid',
            gridTemplateColumns: {xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)'},
            gap: 3,
            alignItems: 'start'
        }}>
            <Stack component="fieldset" disabled={!!saving} spacing={3} sx={{border: 0, p: 0, m: 0, minWidth: 0}}>
                <SectionCard title="服务凭据" icon={<KeyOutlined/>}
                             action={<Typography variant="caption" color="text.secondary">独立保存</Typography>}>
                    <Stack spacing={2.5}>
                        <TextField select label="AI 提供商" value={draft.provider}
                                   onChange={event => set('provider', event.target.value as AISettings['provider'])}><MenuItem
                            value="GEMINI">Google Gemini</MenuItem><MenuItem value="OPENAI">OpenAI（兼容 API）</MenuItem></TextField>
                        {draft.provider === 'GEMINI' ? <SecretField label="Gemini API 密钥" value={draft.geminiApiKey}
                                                                    onChange={event => set('geminiApiKey', event.target.value)}
                                                                    error={keyBytes > 512}/> : <><SecretField
                            label="OpenAI API 密钥" value={draft.openAiApiKey}
                            onChange={event => set('openAiApiKey', event.target.value)}
                            error={keyBytes > 512}/><TextField label="接口基准地址（Base URL）"
                                                               value={draft.openAiBaseUrl}
                                                               onChange={event => set('openAiBaseUrl', event.target.value)}
                                                               placeholder="https://api.openai.com/v1"
                                                               helperText="选填，留空使用默认地址。"
                                                               error={utf8Length(draft.openAiBaseUrl) > 2048}/></>}
                        <Alert severity="info">更换提供商或当前 API 密钥并保存后，服务端会清空已选模型。请先保存凭据，再从下方列表选择模型，或在
                            Telegram 私聊中发送 /model 选择。</Alert>
                    </Stack>
                    {saveButton('credentials', '保存服务凭据', credentialsDirty, () => void saveGroup(credentials, 'credentials'), keyBytes > 512 || utf8Length(draft.openAiBaseUrl) > 2048)}
                </SectionCard>
                <SectionCard title="模型名称" icon={<PsychologyOutlined/>}
                             action={<Typography variant="caption" color="text.secondary">独立保存</Typography>}>
                    <ModelSelector {...models} value={selectedModel} credentialsDirty={credentialsDirty}
                                   busy={disabled} onChange={setModelDraft}
                                   onRefresh={models.refresh}/>
                    {saveButton('model', '保存模型名称', modelDirty, () => void saveGroup({selectedModel}, 'model'),
                        credentialsDirty || models.loading || !!models.error || !models.availableModels.includes(selectedModel) || utf8Length(selectedModel) > 256)}
                </SectionCard>
                <SectionCard title="会话策略" icon={<TuneOutlined/>}
                             action={<Chip label={baseline.agentEnabled ? '已启用' : '未启用'}
                                           color={baseline.agentEnabled ? 'primary' : 'default'} variant="outlined"/>}>
                    <Stack spacing={2.5}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center"
                               gap={2}><Box><Typography variant="subtitle2">启用 AI Agent</Typography><Typography
                            variant="caption"
                            color="text.secondary">接收授权私聊消息并调用模型回复。</Typography></Box><Switch
                            checked={draft.agentEnabled} onChange={event => set('agentEnabled', event.target.checked)}
                            slotProps={{input: {'aria-label': '启用 AI Agent'}}}/></Stack>
                        <Divider/>
                        <TextField label="授权私聊 ID" value={draft.agentChatId}
                                   onChange={event => set('agentChatId', event.target.value)}
                                   error={utf8Length(draft.agentChatId) > 64 || (draft.agentEnabled && !draft.agentChatId.trim())}
                                   helperText="仅授权用户的私聊可用，发送者 ID 与 Chat ID 必须匹配；群聊与频道不可用。"/>
                        <Button size="small" variant="outlined" sx={{alignSelf: 'flex-start'}}
                                disabled={!saved.settings.chatId || saved.settings.chatId.startsWith('-')}
                                onClick={() => set('agentChatId', saved.settings.chatId)}>填入默认 Chat
                            ID（仅私聊）</Button>
                        <TextField label="全局上下文" value={draft.globalContext}
                                   onChange={event => set('globalContext', event.target.value)} multiline minRows={5}
                                   maxRows={12} error={contextBytes > 65536}
                                   helperText={`${contextBytes.toLocaleString()} / 65,536 字节，按 UTF-8 计数。`}/>
                        <TextField label="无回复多久后清理（分钟）" type="number" value={interval}
                                   onChange={event => setInterval(event.target.value)} error={!intervalValid}
                                   slotProps={{htmlInput: {min: 0, max: 2147483647, step: 1}}}
                                   helperText="填写 0 关闭自动清理。"/>
                        <FormControlLabel
                            control={<Switch checked={draft.silentContextCleanup} disabled={interval === '0'}
                                             onChange={event => set('silentContextCleanup', event.target.checked)}/>}
                            label={<Typography variant="body2">静默清理，不向私聊发送通知</Typography>}/>
                        <Typography variant="caption" color="text.secondary">距上次成功 AI
                            回复达到设定时长后，在下次处理消息时清理上下文。</Typography>
                    </Stack>
                    {saveButton('policy', '保存会话设置', policyDirty, () => void saveGroup({
                        agentEnabled: draft.agentEnabled,
                        agentChatId: draft.agentChatId,
                        globalContext: draft.globalContext,
                        autoCleanContextIntervalMinutes: Number(interval),
                        silentContextCleanup: interval === '0' ? false : draft.silentContextCleanup
                    }, 'policy'), !intervalValid || contextBytes > 65536 || utf8Length(draft.agentChatId) > 64 || (draft.agentEnabled && !draft.agentChatId.trim()))}
                </SectionCard>
            </Stack>
            <Stack spacing={3}>
                <McpServers key={mcpEditorEpoch} servers={baseline.mcpServers} busy={disabled}
                            onEditingChange={setMcpEditing}
                            onSave={servers => saveGroup({mcpServers: servers}, 'mcp')}/>
                <SkillCatalog onEditingChange={setSkillEditing}/>
            </Stack>
        </Box>
        <SettingsConflictDialog open={conflict} busy={loading} onClose={() => setConflict(false)}
                                onReload={() => void loadLatest()}/>
        <FeedbackSnackbar notice={notice} onClose={() => setNotice(null)}/>
        <UnsavedChangesGuard dirty={dirty || mcpEditing || skillEditing}/>
    </>;
}
