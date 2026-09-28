import { useAlerts } from "@keycloak/keycloak-ui-shared";
import { Switch } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../admin-client";
import { useAccess } from "../context/access/Access";

type MembershipTypeToggleProps = {
  orgId: string;
  userId: string;
  /** Labels the switch, the member or the organization depending on the table. */
  name?: string;
  isManaged: boolean;
  isDisabled?: boolean;
};

export const MembershipTypeToggle = ({
  orgId,
  userId,
  name,
  isManaged,
  isDisabled = false,
}: MembershipTypeToggleProps) => {
  const { t } = useTranslation();
  const { adminClient } = useAdminClient();
  const { addAlert, addError } = useAlerts();
  const { hasAccess } = useAccess();
  const [checked, setChecked] = useState(isManaged);

  // Rows are recycled by index when the table reloads or the page changes, so
  // pick up the type of whichever row ends up here.
  useEffect(() => setChecked(isManaged), [orgId, userId, isManaged]);

  const update = async (value: boolean) => {
    const previous = checked;
    setChecked(value);
    try {
      await adminClient.organizations.updateMembershipType(
        { orgId, userId },
        value ? "MANAGED" : "UNMANAGED",
      );
      addAlert(t("membershipTypeUpdated"));
    } catch (error) {
      addError("membershipTypeUpdatedError", error);
      setChecked(previous);
    }
  };

  return (
    <Switch
      id={`membershipType-${orgId}-${userId}`}
      data-testid={`membershipType-${orgId}-${userId}`}
      label={t("MANAGED")}
      labelOff={t("UNMANAGED")}
      aria-label={name ?? t("membershipType")}
      isChecked={checked}
      // Releasing a managed member is allowed to organization admins, but
      // making a member managed requires realm-wide rights to manage users.
      isDisabled={isDisabled || (!checked && !hasAccess("manage-users"))}
      onChange={(_event, value) => update(value)}
    />
  );
};
