import { ApiOutlined, KeyOutlined, LockOutlined } from '@ant-design/icons';
import { AutoComplete, Button, Card, Empty, Flex, Form, Input, Modal, Popconfirm, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { formatDate } from '../../accounting/labels';
import PageHeader from '../../components/PageHeader';
import {
  changeApiKey,
  createAgent,
  deleteAgent,
  getAgents,
  type AgentDto,
  type CreateAgentDto,
} from '../../generated/api';
import { useProviderOnboarding } from '../../onboarding/ProviderOnboardingContext';
import { brand } from '../../theme';

const MODEL_PROVIDERS = ['openai', 'anthropic', 'google', 'mistral', 'deepseek'];

/**
 * Agents of the provider: LLM accesses with API key whose spare capacity is offered. Target of the onboarding
 * checklist.
 */
export default function AgentsPage() {
  const onboarding = useProviderOnboarding();
  const [agents, setAgents] = useState<AgentDto[]>();
  const [saving, setSaving] = useState(false);
  const [keyFor, setKeyFor] = useState<AgentDto>();
  const [form] = Form.useForm<CreateAgentDto>();
  const [keyForm] = Form.useForm<{ apiKey: string }>();

  const load = useCallback(async () => {
    const { data } = await getAgents();
    setAgents(data ?? []);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const add = async (values: CreateAgentDto) => {
    setSaving(true);
    const { data } = await createAgent({ body: { ...values, baseUrl: values.baseUrl || undefined } });
    setSaving(false);
    if (!data) {
      void message.error('Bitte prüfe die Angaben: der API-Key braucht mindestens 8 Zeichen, die URL muss mit https beginnen.');
      return;
    }
    void message.success(`${data.name} ist hinterlegt.`);
    form.resetFields();
    void load();
    void onboarding.reload();
  };

  const remove = async (agent: AgentDto) => {
    const { error, response } = await deleteAgent({ path: { id: agent.id } });
    if (response?.status === 409) {
      void message.error(`${agent.name} bedient noch Angebote und kann nicht entfernt werden.`);
      return;
    }
    if (error) {
      void message.error('Der Agent konnte nicht entfernt werden.');
      return;
    }
    void load();
    void onboarding.reload();
  };

  const replaceKey = async ({ apiKey }: { apiKey: string }) => {
    if (!keyFor) return;
    const { data } = await changeApiKey({ path: { id: keyFor.id }, body: { apiKey } });
    if (!data) {
      void message.error('Der API-Key braucht mindestens 8 Zeichen.');
      return;
    }
    void message.success('API-Key ersetzt.');
    setKeyFor(undefined);
    keyForm.resetFields();
    void load();
  };

  return (
    <>
      <PageHeader
        title="Agents"
        description="Deine LLM-Zugänge. Über sie bedienen wir die Aufträge, die zu deinen Angeboten passen."
      />
      <Flex gap={24} wrap align="flex-start">
        <Flex vertical gap={16} style={{ flex: '1 1 380px', minWidth: 0 }}>
          {agents?.length === 0 && (
            <Card>
              <Empty description="Noch kein Agent hinterlegt" />
            </Card>
          )}
          {agents?.map((agent) => (
            <section key={agent.id} style={{ border: `2px solid ${brand.ink}`, borderRadius: 16, padding: 20 }}>
              <Flex justify="space-between" align="flex-start" gap={12} wrap>
                <Flex gap={14} align="flex-start">
                  <ApiOutlined style={{ fontSize: 26, marginTop: 4 }} />
                  <div>
                    <div style={{ fontSize: 18, fontWeight: 700 }}>{agent.name}</div>
                    <Flex gap={6} wrap style={{ margin: '4px 0' }}>
                      <Tag bordered={false}>{agent.modelProvider}</Tag>
                      <Tag bordered={false} icon={<KeyOutlined />}>
                        •••• {agent.apiKeyHint}
                      </Tag>
                    </Flex>
                    <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                      {agent.baseUrl ?? 'Standard-Endpunkt'} · hinzugefügt {formatDate(agent.createdAt)}
                    </Typography.Text>
                  </div>
                </Flex>
                <Flex gap={6}>
                  <Button onClick={() => setKeyFor(agent)}>Key ersetzen</Button>
                  <Popconfirm
                    title={`${agent.name} entfernen?`}
                    description="Der API-Key wird gelöscht."
                    okText="Entfernen"
                    cancelText="Abbrechen"
                    onConfirm={() => remove(agent)}
                  >
                    <Button type="text">Entfernen</Button>
                  </Popconfirm>
                </Flex>
              </Flex>
            </section>
          ))}
        </Flex>
        <Card title="Agent hinzufügen" style={{ flex: '1 1 340px' }}>
          <Form form={form} layout="vertical" requiredMark={false} onFinish={add} initialValues={{ modelProvider: 'openai' }}>
            <Form.Item name="modelProvider" label="Anbieter" rules={[{ required: true, message: 'Wähle den Anbieter.' }]}>
              <AutoComplete options={MODEL_PROVIDERS.map((value) => ({ value }))} />
            </Form.Item>
            <Form.Item
              name="name"
              label="Modell"
              extra="Angebote dieses Agents verkaufen genau dieses Modell."
              rules={[{ required: true, message: 'Gib das Modell an.' }]}
            >
              <Input placeholder="z. B. gpt-5" maxLength={100} />
            </Form.Item>
            <Form.Item name="baseUrl" label="Endpunkt (optional)" extra="Nur für eigene, OpenAI-kompatible Endpunkte.">
              <Input placeholder="https://…" />
            </Form.Item>
            <Form.Item
              name="apiKey"
              label="API-Key"
              rules={[{ required: true, min: 8, message: 'Füge den API-Key ein (mindestens 8 Zeichen).' }]}
            >
              <Input.Password placeholder="sk-…" autoComplete="off" />
            </Form.Item>
            <Flex gap={8} align="center" style={{ color: brand.muted, fontSize: 13, marginBottom: 16 }}>
              <LockOutlined />
              Wir speichern den Key verschlüsselt und zeigen ihn nie wieder an.
            </Flex>
            <Button className="va-accent" size="large" htmlType="submit" loading={saving}>
              Agent hinterlegen
            </Button>
          </Form>
        </Card>
      </Flex>
      <Modal
        open={!!keyFor}
        title={`Neuer API-Key für ${keyFor?.name ?? ''}`}
        okText="Ersetzen"
        cancelText="Abbrechen"
        onCancel={() => setKeyFor(undefined)}
        onOk={() => keyForm.submit()}
        destroyOnHidden
      >
        <Form form={keyForm} layout="vertical" requiredMark={false} onFinish={replaceKey}>
          <Form.Item name="apiKey" label="API-Key" rules={[{ required: true, min: 8, message: 'Mindestens 8 Zeichen.' }]}>
            <Input.Password autoComplete="off" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
