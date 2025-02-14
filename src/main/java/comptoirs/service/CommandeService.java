package comptoirs.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.ConstraintViolationException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;

import comptoirs.dao.ClientRepository;
import comptoirs.dao.CommandeRepository;
import comptoirs.dao.LigneRepository;
import comptoirs.dao.ProduitRepository;
import comptoirs.entity.Commande;
import comptoirs.entity.Ligne;

import jakarta.validation.constraints.Positive;

@Service
@Validated // Les annotations de validation sont actives sur les méthodes de ce service
// (ex: @Positive)
public class CommandeService {
    // La couche "Service" utilise la couche "Accès aux données" pour effectuer les traitements
    private final CommandeRepository commandeDao;
    private final ClientRepository clientDao;
    private final LigneRepository ligneDao;
    private final ProduitRepository produitDao;

    // @Autowired
    // Spring initialisera automatiquement ces paramètres
    public CommandeService(CommandeRepository commandeDao, ClientRepository clientDao, LigneRepository ligneDao,
                           ProduitRepository produitDao) {
        this.commandeDao = commandeDao;
        this.clientDao = clientDao;
        this.ligneDao = ligneDao;
        this.produitDao = produitDao;
    }

    /**
     * Service métier : Enregistre une nouvelle commande pour un client connu par sa clé
     * Règles métier :
     * - le client doit exister
     * - On initialise l'adresse de livraison avec l'adresse du client
     * - Si le client a déjà commandé plus de 100 articles, on lui offre une remise de 15%
     *
     * @param clientCode la clé du client
     * @return la commande créée
     * @throws java.util.NoSuchElementException si le client n'existe pas
     */
    @Transactional
    public Commande creerCommande(@NonNull String clientCode) {
        // On vérifie que le client existe
        var client = clientDao.findById(clientCode).orElseThrow();
        // On crée une commande pour ce client
        var nouvelleCommande = new Commande(client);
        // On initialise l'adresse de livraison avec l'adresse du client
        nouvelleCommande.setAdresseLivraison(client.getAdresse());
        // Si le client a déjà commandé plus de 100 articles, on lui offre une remise de 15%
        // La requête SQL nécessaire est définie dans l'interface ClientRepository
        var nbArticles = clientDao.nombreArticlesCommandesPar(clientCode);
        if (nbArticles > 100) {
            nouvelleCommande.setRemise(new BigDecimal("0.15"));
        }
        // On enregistre la commande (génère la clé)
        commandeDao.save(nouvelleCommande);
        return nouvelleCommande;
    }

    /**
     * <pre>
     * Service métier :
     * Enregistre une nouvelle ligne de commande pour une commande connue par sa clé,
     * Incrémente la quantité totale commandée (Produit.unitesCommandees) avec la quantitÉ à commander
     * Règles métier :
     * - le produit référencé doit exister et ne pas être indisponible
     * - la commande doit exister
     * - la commande ne doit pas être déjà envoyée (le champ 'envoyeele' doit être null)
     * - la quantité doit être positive
     * - La quantité en stock du produit ne doit pas être inférieure au total des quantités commandées
     * <pre>
     * @param commandeNum la clé de la commande
     * @param produitRef  la clé du produit
     * @param quantite    la quantité commandée (positive)
     * @return la ligne de commande créée
     * @throws java.util.NoSuchElementException si la commande ou le produit n'existe pas
     * @throws IllegalStateException si il n'y a pas assez de stock, si la commande a déjà été envoyée,
     * @throws jakarta.validation.ConstraintViolationException si la quantité n'est pas positive
     */
    @Transactional
    public Ligne ajouterLigne(int commandeNum, int produitRef, @Positive int quantite) {

        // Vérifier si la commande existe
        var commande = commandeDao.findById(commandeNum).orElseThrow();

        // Vérifier si la commande est déjà envoyée
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("La commande a déjo été envoyée.");
        }

        // Vérifier si le produit existe
        var produit = produitDao.findById(produitRef).orElseThrow();

        // Vérifier si la quantité en stock est inférieure à la quantité et si la quantité est positive
        if ((produit.getUnitesEnStock() < quantite && quantite > 0) || produit.isIndisponible()) {
            throw new IllegalStateException("La quantité en stock est insuffisante");
        }

        // Mettre à jour la ligne
        var ligne = new Ligne(commande, produit, quantite);
        ligneDao.save(ligne);

        // Mettre à jour le produit
        produit.setUnitesCommandees(produit.getUnitesCommandees() + quantite);
        produitDao.save(produit);

        return ligne;
    }

    /**
     * Service métier : Enregistre l'expédition d'une commande connue par sa clé
     * Règles métier :
     * - la commande doit exister
     * - la commande ne doit pas être déjà envoyée (le champ 'envoyeele' doit être null)
     * - On renseigne la date d'expédition (envoyeele) avec la date du jour
     * - Pour chaque produit dans les lignes de la commande :
     * décrémente la quantité en stock (Produit.unitesEnStock) de la quantité dans la commande
     * décrémente la quantité commandée (Produit.unitesCommandees) de la quantité dans la commande
     *
     * @param commandeNum la clé de la commande
     * @return la commande mise à jour
     * @throws java.util.NoSuchElementException si la commande n'existe pas
     * @throws IllegalStateException            si la commande a déjà été envoyée
     */
    @Transactional
    public Commande enregistreExpedition(int commandeNum) {

        // Vérifier si la commande existe
        var commande = commandeDao.findById(commandeNum).orElseThrow();

        // Vérifier si la commande est déjà envoyée
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("La commande a déjo été envoyée.");
        }

        // Envoyer la commande
        commande.setEnvoyeele(LocalDate.now());

        // Parcours les lignes de la commande
        for (Ligne ligne : commande.getLignes()) {
            // Récupérer le produit et la quantité dans la ligne
            var produit = ligne.getProduit();
            var quantiteCommandee = ligne.getQuantite();

            // Mettre à jour la quantité en stock et la quantité commandée
            produit.setUnitesEnStock(produit.getUnitesEnStock() - quantiteCommandee);
            produit.setUnitesCommandees(produit.getUnitesCommandees() - quantiteCommandee);

            produitDao.save(produit);
        }

        // Sauvegarder la commande
        commandeDao.save(commande);
        return commande;
    }

}
