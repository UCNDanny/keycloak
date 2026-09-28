import {
  FormSubmitButton,
  KeycloakSpinner,
  useAlerts,
  useFetch,
} from "@keycloak/keycloak-ui-shared";
import {
  ActionGroup,
  Button,
  PageSection,
  Tab,
  Tabs,
  TabTitleText,
} from "@patternfly/react-core";
import OrganizationRepresentation from "@keycloak/keycloak-admin-client/lib/defs/organizationRepresentation";
import { NetworkError } from "@keycloak/keycloak-admin-client";
import { FormProvider, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../admin-client";
import { FormAccess } from "../components/form/FormAccess";
import { AttributesForm } from "../components/key-value-form/AttributeForm";
import { arrayToKeyValue } from "../components/key-value-form/key-value-convert";
import {
  RoutableTabs,
  useRoutableTab,
} from "../components/routable-tabs/RoutableTabs";
import { useRealm } from "../context/realm-context/RealmContext";
import { useParams } from "../utils/useParams";
import { DetailOrganizationHeader } from "./DetailOraganzationHeader";
import { IdentityProviders } from "./IdentityProviders";
import { MembersSection } from "./MembersSection";
import GroupsSection from "../groups/GroupsSection";
import {
  OrganizationForm,
  OrganizationFormType,
  convertToOrg,
} from "./OrganizationForm";
import {
  EditOrganizationParams,
  OrganizationTab,
  toEditOrganization,
} from "./routes/EditOrganization";
import { useAccess } from "../context/access/Access";
import { ForbiddenSection } from "../ForbiddenSection";
import { AdminEvents } from "../events/AdminEvents";
import { useState } from "react";

const toFormValues = (
  org: OrganizationRepresentation,
): OrganizationFormType => ({
  ...org,
  domains: org.domains
    ?.map((d) => d.name)
    .filter((name): name is string => !!name),
  attributes: arrayToKeyValue(org.attributes),
});

export default function DetailOrganization() {
  const { adminClient } = useAdminClient();
  const { id } = useParams<EditOrganizationParams>();
  const { t } = useTranslation();

  const [organization, setOrganization] =
    useState<OrganizationRepresentation>();
  const [forbiddenId, setForbiddenId] = useState<string>();

  useFetch(
    async () => {
      try {
        return (await adminClient.organizations.findOne({ id })) as
          | OrganizationRepresentation
          | undefined;
      } catch (error) {
        if (error instanceof NetworkError && error.response.status === 403) {
          setForbiddenId(id);
          return null;
        }
        throw error;
      }
    },
    (org) => {
      if (org === null) {
        return;
      }
      if (!org) {
        throw new Error(t("notFound"));
      }
      setOrganization(org);
    },
    [id],
  );

  if (forbiddenId === id) {
    return <ForbiddenSection permissionNeeded="view-organizations" />;
  }

  if (organization?.id !== id) {
    return <KeycloakSpinner />;
  }

  return <OrganizationDetails key={id} organization={organization} />;
}

type OrganizationDetailsProps = {
  organization: OrganizationRepresentation;
};

const OrganizationDetails = ({ organization }: OrganizationDetailsProps) => {
  const { adminClient } = useAdminClient();
  const { addAlert, addError } = useAlerts();

  const { realm, realmRepresentation } = useRealm();
  const { id } = useParams<EditOrganizationParams>();
  const { t } = useTranslation();

  const form = useForm<OrganizationFormType>({
    defaultValues: toFormValues(organization),
  });
  const canManage = organization.access?.manage ?? false;
  const { hasAccess, hasSomeAccess } = useAccess();
  const canManageDomains =
    canManage && hasSomeAccess("manage-organizations", "manage-realm");

  const save = async (org: OrganizationFormType) => {
    try {
      const updated = convertToOrg(org);
      // keep the stored domains, including their verified state, when the domains are read-only
      if (!canManageDomains) {
        updated.domains = organization.domains;
      }
      await adminClient.organizations.updateById({ id }, updated);
      addAlert(t("organizationSaveSuccess"));
    } catch (error) {
      addError("organizationSaveError", error);
    }
  };

  const useTab = (tab: OrganizationTab) =>
    useRoutableTab(
      toEditOrganization({
        realm,
        id,
        tab,
      }),
    );

  const settingsTab = useTab("settings");
  const attributesTab = useTab("attributes");
  const membersTab = useTab("members");
  const groupsTab = useTab("groups");
  const identityProvidersTab = useTab("identityProviders");
  const eventsTab = useTab("events");

  const [activeEventsTab, setActiveEventsTab] = useState("adminEvents");

  return (
    <PageSection variant="light" className="pf-v5-u-p-0">
      <FormProvider {...form}>
        <DetailOrganizationHeader
          save={() => save(form.getValues())}
          canManage={canManage}
        />
        <RoutableTabs
          data-testid="organization-tabs"
          aria-label={t("organization")}
          isBox
          mountOnEnter
        >
          <Tab
            id="settings"
            data-testid="settingsTab"
            title={<TabTitleText>{t("settings")}</TabTitleText>}
            {...settingsTab}
          >
            <PageSection>
              <FormAccess
                role="anyone"
                isReadOnly={!canManage}
                onSubmit={form.handleSubmit(save)}
                isHorizontal
              >
                <OrganizationForm
                  readOnly
                  isDisabled={!canManage}
                  isDomainsDisabled={!canManageDomains}
                />
                {canManage && (
                  <ActionGroup>
                    <FormSubmitButton
                      formState={form.formState}
                      data-testid="save"
                    >
                      {t("save")}
                    </FormSubmitButton>
                    <Button
                      onClick={() => form.reset()}
                      data-testid="reset"
                      variant="link"
                    >
                      {t("reset")}
                    </Button>
                  </ActionGroup>
                )}
              </FormAccess>
            </PageSection>
          </Tab>
          <Tab
            id="attributes"
            data-testid="attributeTab"
            title={<TabTitleText>{t("attributes")}</TabTitleText>}
            {...attributesTab}
          >
            <PageSection variant="light">
              <AttributesForm
                form={form}
                save={canManage ? save : undefined}
                reset={
                  canManage
                    ? () =>
                        form.reset({
                          ...form.getValues(),
                        })
                    : undefined
                }
                fineGrainedAccess={canManage}
                isDisabled={!canManage}
                name="attributes"
              />
            </PageSection>
          </Tab>
          <Tab
            id="members"
            data-testid="membersTab"
            title={<TabTitleText>{t("members")}</TabTitleText>}
            {...membersTab}
          >
            <MembersSection canManage={canManage} />
          </Tab>
          <Tab
            id="groups"
            data-testid="groupsTab"
            title={<TabTitleText>{t("groups")}</TabTitleText>}
            {...groupsTab}
          >
            <GroupsSection orgId={id} />
          </Tab>
          <Tab
            id="identityProviders"
            data-testid="identityProvidersTab"
            title={<TabTitleText>{t("identityProviders")}</TabTitleText>}
            {...identityProvidersTab}
          >
            <IdentityProviders canManage={canManage} />
          </Tab>
          {realmRepresentation.adminEventsEnabled &&
            hasAccess("view-events") && (
              <Tab
                data-testid="admin-events-tab"
                title={<TabTitleText>{t("adminEvents")}</TabTitleText>}
                {...eventsTab}
              >
                <Tabs
                  activeKey={activeEventsTab}
                  onSelect={(_, key) => setActiveEventsTab(key as string)}
                >
                  <Tab
                    eventKey="adminEvents"
                    title={<TabTitleText>{t("adminEvents")}</TabTitleText>}
                  >
                    <AdminEvents resourcePath={`organizations/${id}`} />
                  </Tab>
                  <Tab
                    eventKey="membershipEvents"
                    title={<TabTitleText>{t("membershipEvents")}</TabTitleText>}
                  >
                    <AdminEvents resourcePath={`organizations/${id}/members`} />
                  </Tab>
                </Tabs>
              </Tab>
            )}
        </RoutableTabs>
      </FormProvider>
    </PageSection>
  );
};
