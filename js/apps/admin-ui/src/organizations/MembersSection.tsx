import { Tab, Tabs, TabTitleText } from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Invitations } from "./Invitations";
import { Members } from "./Members";

type MembersSectionProps = {
  canManage: boolean;
};

export const MembersSection = ({ canManage }: MembersSectionProps) => {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState("members");

  return (
    <Tabs
      activeKey={activeTab}
      onSelect={(_, key) => setActiveTab(key as string)}
    >
      <Tab
        eventKey="members"
        title={<TabTitleText>{t("members")}</TabTitleText>}
        data-testid="organization-members-tab"
      >
        <Members canManage={canManage} />
      </Tab>
      {canManage && (
        <Tab
          eventKey="invitations"
          title={<TabTitleText>{t("invitations")}</TabTitleText>}
          data-testid="organization-invitations-tab"
        >
          <Invitations />
        </Tab>
      )}
    </Tabs>
  );
};
